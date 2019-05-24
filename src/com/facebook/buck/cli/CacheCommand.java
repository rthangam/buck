/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheCountersSummary;
import com.facebook.buck.artifact_cache.CacheCountersSummaryEvent;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** A command for inspecting the artifact cache. */
public class CacheCommand extends AbstractCommand {

  @Option(name = "--output-dir", usage = "Extract artifacts to this directory.")
  @Nullable
  private String outputDir = null;

  @Option(name = "--distributed", usage = "If the request is for our distributed system.")
  private boolean isRequestForDistributed = false;

  @Option(
      name = "--rule-key-with-target",
      usage =
          "A build target and rule key pair which will be sent to the cache together. "
              + "This is in contrast to specifying a rule key on its own (without this flag), which will "
              + "be sent to the cache without any target information. This flag can be specified "
              + "multiple times so each rule key can be sent to the cache with a target.",
      handler = PairedStringOptionHandler.class)
  private List<Pair<String, String>> targetsWithRuleKeys = new ArrayList<>();

  @VisibleForTesting
  void setTargetsWithRuleKeys(ImmutableList<Pair<String, String>> pairs) {
    this.targetsWithRuleKeys = pairs;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  Optional<Path> outputPath = Optional.empty();

  public void fakeOutParseEvents(BuckEventBus eventBus) {
    ParseEvent.Started parseStart = ParseEvent.started(ImmutableList.of());
    eventBus.post(parseStart);
    eventBus.post(ParseEvent.finished(parseStart, 0, Optional.empty()));
    ActionGraphEvent.Started actionGraphStart = ActionGraphEvent.started();
    eventBus.post(actionGraphStart);
    eventBus.post(ActionGraphEvent.finished(actionGraphStart));
  }

  // TODO(nga): enable warning back
  @VisibleForTesting static final boolean MUTE_FETCH_SUBCOMMAND_WARNING = true;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {

    params.getBuckEventBus().post(ConsoleEvent.fine("cache command start"));

    if (isNoCache()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("Caching is disabled."));
      return ExitCode.NOTHING_TO_DO;
    }

    List<String> arguments = getArguments();

    // Lack of arguments implicitly means fetch now. The old behaviour will be deprecated soon.
    // TODO(nga): Remove old behaviour of buck cache.
    if (!arguments.isEmpty() && arguments.get(0).equals("fetch")) {
      arguments = arguments.subList(1, arguments.size());
    } else {
      if (!MUTE_FETCH_SUBCOMMAND_WARNING) {
        params
            .getConsole()
            .printErrorText(
                "Using `cache` without a command is deprecated, use `cache fetch` instead");
      }
    }

    if (arguments.isEmpty() && targetsWithRuleKeys.isEmpty()) {
      throw new CommandLineException("no cache keys specified");
    }

    if (outputDir != null) {
      outputPath = Optional.of(Paths.get(outputDir));
      Files.createDirectories(outputPath.get());
    }

    ImmutableList<RuleKey> rawRuleKeys =
        arguments.stream().map(RuleKey::new).collect(ImmutableList.toImmutableList());

    ImmutableList<Pair<BuildTarget, RuleKey>> pairedRuleKeys =
        targetsWithRuleKeys.stream()
            .map(pair -> parseTargetRuleKeyPair(params, pair))
            .collect(ImmutableList.toImmutableList());

    Path tmpDir = Files.createTempDirectory("buck-cache-command");

    BuildEvent.Started started = BuildEvent.started(getArguments());

    List<ArtifactRunner> results = null;
    try (ArtifactCache cache =
            params.getArtifactCacheFactory().newInstance(isRequestForDistributed, false);
        CommandThreadManager pool =
            new CommandThreadManager("Build", getConcurrencyLimit(params.getBuckConfig()))) {
      WeightedListeningExecutorService executor = pool.getWeightedListeningExecutorService();

      fakeOutParseEvents(params.getBuckEventBus());

      params.getBuckEventBus().post(started);

      // Fetch all artifacts
      List<ListenableFuture<ArtifactRunner>> futures = new ArrayList<>();
      for (RuleKey ruleKey : rawRuleKeys) {
        futures.add(
            executor.submit(
                new ArtifactRunner(
                    params.getProjectFilesystemFactory(), null, ruleKey, tmpDir, cache)));
      }

      for (Pair<BuildTarget, RuleKey> targetRuleKeyPair : pairedRuleKeys) {
        futures.add(
            executor.submit(
                new ArtifactRunner(
                    params.getProjectFilesystemFactory(),
                    targetRuleKeyPair.getFirst(),
                    targetRuleKeyPair.getSecond(),
                    tmpDir,
                    cache)));
      }

      // Wait for all executions to complete or fail.
      try {
        results = Futures.allAsList(futures).get();
      } catch (ExecutionException ex) {
        params.getConsole().printBuildFailure("Failed");
        ex.printStackTrace(params.getConsole().getStdErr());
      }
    }

    int totalRuns = results.size();
    StringBuilder resultString = new StringBuilder();
    int goodRuns = 0;

    HashMap<ArtifactCacheMode, AtomicInteger> cacheHitsPerMode = new HashMap<>();
    HashMap<ArtifactCacheMode, AtomicInteger> cacheErrorsPerMode = new HashMap<>();
    for (ArtifactCacheMode mode : ArtifactCacheMode.values()) {
      cacheHitsPerMode.put(mode, new AtomicInteger(0));
      cacheErrorsPerMode.put(mode, new AtomicInteger(0));
    }
    int cacheHits = 0;
    int cacheMisses = 0;
    int cacheErrors = 0;
    int cacheIgnored = 0;
    int localKeyUnchanged = 0;

    for (ArtifactRunner r : results) {
      if (r.completed) {
        goodRuns++;
      }
      resultString.append(r.resultString);
      ArtifactCacheMode artifactCacheMode = r.cacheResultMode.orElse(ArtifactCacheMode.unknown);
      switch (r.cacheResultType) {
        case ERROR:
          if (cacheErrorsPerMode.containsKey(artifactCacheMode)) {
            cacheErrorsPerMode.get(artifactCacheMode).incrementAndGet();
          }
          ++cacheErrors;
          break;
        case CONTAINS:
          // Ignore it since it will be counted as a hit later.
          break;
        case HIT:
          if (cacheHitsPerMode.containsKey(artifactCacheMode)) {
            cacheHitsPerMode.get(artifactCacheMode).incrementAndGet();
          }
          ++cacheHits;
          break;
        case MISS:
          ++cacheMisses;
          break;
        case IGNORED:
          ++cacheIgnored;
          break;
        case LOCAL_KEY_UNCHANGED_HIT:
          ++localKeyUnchanged;
          break;
        case SKIPPED:
          break;
      }

      if (!outputPath.isPresent()) {
        // legacy output
        if (r.completed) {
          params.getConsole().getStdOut().println(resultString);

          params
              .getConsole()
              .printSuccess(
                  String.format(
                      "Successfully downloaded artifact with id %s at %s.", r.ruleKey, r.artifact));
        } else {
          params
              .getConsole()
              .printErrorText(
                  String.format(
                      "Failed to retrieve an artifact with id %s (%s).", r.ruleKey, r.cacheResult));
        }
      }
    }

    params
        .getBuckEventBus()
        .post(
            CacheCountersSummaryEvent.newSummary(
                CacheCountersSummary.builder()
                    .setCacheHitsPerMode(cacheHitsPerMode)
                    .setCacheErrorsPerMode(cacheErrorsPerMode)
                    .setTotalCacheHits(cacheHits)
                    .setTotalCacheErrors(cacheErrors)
                    .setTotalCacheMisses(cacheMisses)
                    .setTotalCacheIgnores(cacheIgnored)
                    .setTotalCacheLocalKeyUnchangedHits(localKeyUnchanged)
                    .setFailureUploadCount(new AtomicInteger(0))
                    .setSuccessUploadCount(new AtomicInteger(0))
                    .build()));

    ExitCode exitCode = (totalRuns == goodRuns) ? ExitCode.SUCCESS : ExitCode.BUILD_ERROR;
    params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));

    if (outputPath.isPresent()) {
      if (totalRuns == goodRuns) {
        params.getConsole().printSuccess("Successfully downloaded all artifacts.");
      } else {
        params
            .getConsole()
            .printErrorText(String.format("Downloaded %d of %d artifacts", goodRuns, totalRuns));
      }
      params.getConsole().getStdOut().println(resultString);
    }

    return exitCode;
  }

  private Pair<BuildTarget, RuleKey> parseTargetRuleKeyPair(
      CommandRunnerParams params, Pair<String, String> rawArg) {
    String targetName = rawArg.getFirst();
    String ruleKey = rawArg.getSecond();

    BuildTarget buildTarget =
        params
            .getUnconfiguredBuildTargetFactory()
            .create(params.getCell().getCellPathResolver(), targetName)
            .configure(params.getTargetConfiguration());
    return new Pair<>(buildTarget, new RuleKey(ruleKey));
  }

  private String cacheResultToString(CacheResult cacheResult) {
    CacheResultType type = cacheResult.getType();
    String typeString = type.toString().toLowerCase();
    switch (type) {
      case ERROR:
        return String.format("%s %s", typeString, cacheResult.getCacheError());
      case HIT:
      case CONTAINS:
        return String.format("%s %s", typeString, cacheResult.getCacheSource());
      case SKIPPED:
      case MISS:
      case IGNORED:
      case LOCAL_KEY_UNCHANGED_HIT:
      default:
        return typeString;
    }
  }

  private boolean extractArtifact(
      ProjectFilesystemFactory projectFilesystemFactory,
      Path outputPath,
      Path tmpDir,
      RuleKey ruleKey,
      Path artifact,
      CacheResult success,
      StringBuilder resultString)
      throws InterruptedException {

    String buckTarget = "Unknown Target";
    ImmutableMap<String, String> metadata = success.getMetadata();
    if (metadata.containsKey(BuildInfo.MetadataKey.TARGET)) {
      buckTarget = success.metadata().get().get(BuildInfo.MetadataKey.TARGET);
    }
    ImmutableList<Path> paths;
    try {
      paths =
          ArchiveFormat.TAR_ZSTD
              .getUnarchiver()
              .extractArchive(
                  projectFilesystemFactory,
                  artifact.toAbsolutePath(),
                  tmpDir,
                  ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    } catch (IOException e) {
      resultString.append(String.format("%s %s !(Unable to extract) %s\n", ruleKey, buckTarget, e));
      return false;
    }
    int filesMoved = 0;
    for (Path path : paths) {
      if (path.getParent().getFileName().toString().equals("metadata")) {
        continue;
      }
      Path relative = tmpDir.relativize(path);
      Path destination = outputPath.resolve(relative);
      try {
        Files.createDirectories(destination.getParent());
        try {
          Files.move(path, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
          String differentMountError =
              String.format(
                  "%s and %s are not on the same mount. Falling back to non atomic move\n",
                  path, destination);
          Files.move(path, destination);
          // We are adding it after moving file, so in case we fail due to
          // different reason, we won't show message twice.
          resultString.append(differentMountError);
        }
        resultString.append(String.format("%s %s => %s\n", ruleKey, buckTarget, relative));
        filesMoved += 1;
      } catch (IOException e) {
        resultString.append(
            String.format(
                "%s %s !(could not move file: %s) %s\n",
                ruleKey, buckTarget, e.getMessage(), relative));
        return false;
      }
    }
    if (filesMoved == 0) {
      resultString.append(String.format("%s %s !(Nothing to extract)\n", ruleKey, buckTarget));
    }
    return filesMoved > 0;
  }

  @Override
  public String getShortDescription() {
    return "makes calls to the artifact cache";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  class ArtifactRunner implements Callable<ArtifactRunner> {

    final ProjectFilesystemFactory projectFilesystemFactory;
    @Nullable BuildTarget buildTarget;
    RuleKey ruleKey;
    Path tmpDir;
    Path artifact;
    CacheResultType cacheResultType;
    Optional<ArtifactCacheMode> cacheResultMode;
    String statusString;
    String cacheResult;
    StringBuilder resultString;
    ArtifactCache cache;
    boolean completed;

    public ArtifactRunner(
        ProjectFilesystemFactory projectFilesystemFactory,
        @Nullable BuildTarget buildTarget,
        RuleKey ruleKey,
        Path tmpDir,
        ArtifactCache cache) {
      this.projectFilesystemFactory = projectFilesystemFactory;
      this.buildTarget = buildTarget;
      this.ruleKey = ruleKey;
      this.tmpDir = tmpDir;
      this.cache = cache;
      this.artifact = tmpDir.resolve(ruleKey.toString());
      this.statusString = "Created";
      this.cacheResult = "Unknown";
      this.resultString = new StringBuilder();
      this.completed = false;
      this.cacheResultType = CacheResultType.IGNORED;
      this.cacheResultMode = Optional.of(ArtifactCacheMode.unknown);
    }

    @Override
    public String toString() {
      return String.format("ruleKey: %s status: %s cache: %s", ruleKey, statusString, cacheResult);
    }

    @Override
    public ArtifactRunner call() throws InterruptedException, JsonProcessingException {
      statusString = "Fetching";
      // TODO(skotch): don't use intermediate files, that just slows us down
      // instead, unzip from the ~/buck-cache/ directly
      CacheResult success =
          Futures.getUnchecked(
              cache.fetchAsync(buildTarget, ruleKey, LazyPath.ofInstance(artifact)));
      cacheResult = cacheResultToString(success);
      cacheResultType = success.getType();
      cacheResultMode = success.cacheMode();
      ImmutableMap<String, String> metadata =
          success.metadata().orElse(ImmutableMap.<String, String>builder().build());
      resultString.append("Artifact metadata:\n");
      resultString.append(ObjectMappers.WRITER.writeValueAsString(metadata));
      resultString.append(System.lineSeparator());
      boolean cacheSuccess = success.getType().isSuccess();
      if (!cacheSuccess) {
        statusString = String.format("FAILED FETCHING %s %s", ruleKey, cacheResult);
        resultString.append(String.format("%s !(Failed to retrieve an artifact)\n", ruleKey));
      } else if (!outputPath.isPresent()) {
        this.completed = true;
        statusString = "SUCCESS";
        resultString.append(String.format("%s !success\n", ruleKey));
      } else {
        statusString = "Extracting";
        if (extractArtifact(
            projectFilesystemFactory,
            outputPath.get(),
            tmpDir,
            ruleKey,
            artifact,
            success,
            this.resultString)) {
          this.completed = true;
          statusString = "SUCCESS";
        } else {
          statusString = "FAILED Extracting";
        }
      }
      return this;
    }
  }
}
