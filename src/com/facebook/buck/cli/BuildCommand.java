/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleDsym;
import com.facebook.buck.command.Build;
import com.facebook.buck.command.LocalBuildExecutor;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.listener.CriticalPathEventListener;
import com.facebook.buck.event.listener.FileSerializationOutputRuleDepsListener;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.concurrent.ExecutorPool;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class BuildCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(BuildCommand.class);

  private static final String KEEP_GOING_LONG_ARG = "--keep-going";
  private static final String BUILD_REPORT_LONG_ARG = "--build-report";
  private static final String JUST_BUILD_LONG_ARG = "--just-build";
  private static final String DEEP_LONG_ARG = "--deep";
  private static final String OUT_LONG_ARG = "--out";
  private static final String POPULATE_CACHE_LONG_ARG = "--populate-cache";
  private static final String SHALLOW_LONG_ARG = "--shallow";
  private static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";
  private static final String SHOW_OUTPUT_LONG_ARG = "--show-output";
  private static final String SHOW_FULL_OUTPUT_LONG_ARG = "--show-full-output";
  private static final String SHOW_JSON_OUTPUT_LONG_ARG = "--show-json-output";
  private static final String SHOW_FULL_JSON_OUTPUT_LONG_ARG = "--show-full-json-output";
  private static final String SHOW_RULEKEY_LONG_ARG = "--show-rulekey";
  private static final String LOCAL_BUILD_LONG_ARG = "--local";
  private static final String DISTRIBUTED_LONG_ARG = "--distributed";
  static final String BUCK_BINARY_STRING_ARG = "--buck-binary";
  private static final String RULEKEY_LOG_PATH_LONG_ARG = "--rulekeys-log-path";
  private static final String CRITICAL_PATH_FILE_NAME = "critical_path.log";

  private static final String OUTPUT_RULE_DEPS_TO_FILE_ARG = "--output-rule-deps-to-file";
  private static final String ACTION_GRAPH_FILE_NAME = "action_graph.json";
  private static final String RULE_EXEC_TIME_FILE_NAME = "rule_exec_time.json";

  @Option(name = KEEP_GOING_LONG_ARG, usage = "Keep going when some targets can't be made.")
  private boolean keepGoing = false;

  @Option(name = BUILD_REPORT_LONG_ARG, usage = "File where build report will be written.")
  @Nullable
  private Path buildReport = null;

  @Nullable
  @Option(
      name = JUST_BUILD_LONG_ARG,
      usage = "For debugging, limits the build to a specific target in the action graph.",
      hidden = true)
  private String justBuildTarget = null;

  @Option(
      name = DEEP_LONG_ARG,
      usage =
          "Perform a \"deep\" build, which makes the output of all transitive dependencies"
              + " available.",
      forbids = SHALLOW_LONG_ARG)
  private boolean deepBuild = false;

  @Option(
      name = POPULATE_CACHE_LONG_ARG,
      usage =
          "Performs a cache population, which makes the output of all unchanged "
              + "transitive dependencies available (if these outputs are available "
              + "in the remote cache). Does not build changed or unavailable dependencies locally.",
      forbids = {SHALLOW_LONG_ARG, DEEP_LONG_ARG})
  private boolean populateCacheOnly = false;

  @Option(
      name = SHALLOW_LONG_ARG,
      usage =
          "Perform a \"shallow\" build, which only makes the output of all explicitly listed"
              + " targets available.",
      forbids = DEEP_LONG_ARG)
  private boolean shallowBuild = false;

  @Option(
      name = REPORT_ABSOLUTE_PATHS,
      usage = "Reports errors using absolute paths to the source files instead of relative paths.")
  private boolean shouldReportAbsolutePaths = false;

  @Option(
      name = SHOW_OUTPUT_LONG_ARG,
      usage = "Print the path to the output for each of the built rules relative to the cell.")
  private boolean showOutput;

  @Option(name = OUT_LONG_ARG, usage = "Copies the output of the lone build target to this path.")
  @Nullable
  private Path outputPathForSingleBuildTarget;

  @Option(
      name = SHOW_FULL_OUTPUT_LONG_ARG,
      usage = "Print the absolute path to the output for each of the built rules.")
  private boolean showFullOutput;

  @Option(name = SHOW_JSON_OUTPUT_LONG_ARG, usage = "Show output in JSON format.")
  private boolean showJsonOutput;

  @Option(name = SHOW_FULL_JSON_OUTPUT_LONG_ARG, usage = "Show full output in JSON format.")
  private boolean showFullJsonOutput;

  @Option(name = SHOW_RULEKEY_LONG_ARG, usage = "Print the rulekey for each of the built rules.")
  private boolean showRuleKey;

  @Option(
      name = LOCAL_BUILD_LONG_ARG,
      usage = "Disable distributed build (overrides --distributed).")
  private boolean forceDisableDistributedBuild = false;

  @Option(
      name = DISTRIBUTED_LONG_ARG,
      usage = "Whether to run in distributed build mode. (experimental)",
      hidden = true)
  private boolean useDistributedBuild = false; // Must be accessed via the getter method.

  @Nullable
  @Option(
      name = DistBuildRunCommand.BUILD_STATE_FILE_ARG_NAME,
      usage = DistBuildRunCommand.BUILD_STATE_FILE_ARG_USAGE,
      hidden = true)
  private String distributedBuildStateFile = null;

  @Nullable
  @Option(
      name = BUCK_BINARY_STRING_ARG,
      usage = "Buck binary to use on a distributed build instead of the current git version.",
      hidden = true)
  private String buckBinary = null;

  @Nullable
  @Option(
      name = RULEKEY_LOG_PATH_LONG_ARG,
      usage = "If set, log a binary representation of rulekeys to this file.")
  private String ruleKeyLogPath = null;

  @Option(
      name = OUTPUT_RULE_DEPS_TO_FILE_ARG,
      usage = "Serialize rule dependencies and execution time to the log directory")
  private boolean outputRuleDeps = false;

  @Argument private List<String> arguments = new ArrayList<>();

  @Nullable private DistBuildCommandDelegate distBuildCommandDelegate;

  public List<String> getArguments() {
    return arguments;
  }

  public boolean isCodeCoverageEnabled() {
    return false;
  }

  public boolean isDebugEnabled() {
    return false;
  }

  public BuildCommand() {
    this(ImmutableList.of());
  }

  public BuildCommand(List<String> arguments) {
    this.arguments.addAll(arguments);
  }

  public Optional<BuildType> getBuildEngineMode() {
    Optional<BuildType> mode = Optional.empty();
    if (deepBuild) {
      mode = Optional.of(BuildType.DEEP);
    }
    if (populateCacheOnly) {
      mode = Optional.of(BuildType.POPULATE_FROM_REMOTE_CACHE);
    }
    if (shallowBuild) {
      mode = Optional.of(BuildType.SHALLOW);
    }
    return mode;
  }

  public boolean isKeepGoing() {
    return keepGoing;
  }

  protected boolean shouldReportAbsolutePaths() {
    return shouldReportAbsolutePaths;
  }

  public void setKeepGoing(boolean keepGoing) {
    this.keepGoing = keepGoing;
  }

  public void forceDisableDistributedBuild() {
    forceDisableDistributedBuild = true;
  }

  /** Whether this build is using stampede or not. */
  public boolean isUsingDistributedBuild() {
    if (forceDisableDistributedBuild) {
      useDistributedBuild = false;
    }

    return useDistributedBuild;
  }

  @Nullable
  public DistBuildCommandDelegate getDistBuildCommandDelegate() {
    if (isUsingDistributedBuild()) {
      if (distBuildCommandDelegate == null) {
        distBuildCommandDelegate =
            new DistBuildCommandDelegate(
                distributedBuildStateFile, buckBinary, localRuleKeyCalculator, keepGoing);
      }
      return distBuildCommandDelegate;
    }
    return null;
  }

  /**
   * Mark this build as being automatically converted to stampede.
   *
   * @param config to retrieve the message (if any) to be shown to the user.
   * @return true if the build was converted to stampede.
   */
  public boolean tryConvertingToStampede(DistBuildConfig config) {
    if (forceDisableDistributedBuild) {
      LOG.info(
          "Distributed build was forcefully disabled. Will not auto-convert build to stampede.");

      useDistributedBuild = false; // Make sure
      return false;
    }

    useDistributedBuild = true;

    Objects.requireNonNull(getDistBuildCommandDelegate()).tryConvertingToStampede(config);

    return true;
  }

  /** @return an absolute path or {@link Optional#empty()}. */
  public Optional<Path> getPathToBuildReport(BuckConfig buckConfig) {
    return Optional.ofNullable(
        buckConfig.resolvePathThatMayBeOutsideTheProjectFilesystem(buildReport));
  }

  private final AtomicReference<Build> lastBuild = new AtomicReference<>(null);
  private final SettableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculator =
      SettableFuture.create();

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    return runWithoutHelpInternal(params).getExitCode();
  }

  BuildRunResult runWithoutHelpInternal(CommandRunnerParams params) throws Exception {
    assertArguments(params);

    BuckEventBus buckEventBus = params.getBuckEventBus();
    if (outputRuleDeps) {
      FileSerializationOutputRuleDepsListener fileSerializationOutputRuleDepsListener =
          new FileSerializationOutputRuleDepsListener(
              getSimulatorDir(params).resolve(RULE_EXEC_TIME_FILE_NAME));
      buckEventBus.register(fileSerializationOutputRuleDepsListener);
    }

    buckEventBus.register(
        new CriticalPathEventListener(getCriticalPathDir(params).resolve(CRITICAL_PATH_FILE_NAME)));

    try (CommandThreadManager pool =
            new CommandThreadManager("Build", getConcurrencyLimit(params.getBuckConfig()));
        BuildPrehook prehook = getPrehook(new ListeningProcessExecutor(), params)) {
      prehook.startPrehookScript();
      return run(params, pool, Function.identity(), ImmutableSet.of());
    }
  }

  private Path getSimulatorDir(CommandRunnerParams params) throws IOException {
    ProjectFilesystem filesystem = params.getCell().getFilesystem();
    Path simulatorDir = filesystem.resolve(filesystem.getBuckPaths().getSimulatorDir());
    filesystem.mkdirs(simulatorDir);
    return simulatorDir;
  }

  private Path getCriticalPathDir(CommandRunnerParams params) throws IOException {
    Path logDirectoryPath = params.getInvocationInfo().get().getLogDirectoryPath();
    ProjectFilesystem filesystem = params.getCell().getFilesystem();
    Path criticalPathDir = filesystem.resolve(logDirectoryPath);
    filesystem.mkdirs(criticalPathDir);
    return criticalPathDir;
  }

  BuildPrehook getPrehook(ListeningProcessExecutor processExecutor, CommandRunnerParams params) {
    return new BuildPrehook(
        processExecutor,
        params.getCell(),
        params.getBuckEventBus(),
        params.getBuckConfig(),
        params.getEnvironment(),
        getArguments());
  }

  /** @throws CommandLineException if arguments provided are incorrect */
  protected void assertArguments(CommandRunnerParams params) {
    if (!getArguments().isEmpty()) {
      return;
    }
    String message =
        "Must specify at least one build target. See https://buck.build/concept/build_target_pattern.html";
    ImmutableSet<String> aliases = AliasConfig.from(params.getBuckConfig()).getAliases().keySet();
    if (!aliases.isEmpty()) {
      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      message +=
          String.format(
              "%nTry building one of the following targets:%n%s",
              Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10)));
    }
    throw new CommandLineException(message);
  }

  protected BuildRunResult run(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      ImmutableSet<String> additionalTargets)
      throws Exception {
    if (!additionalTargets.isEmpty()) {
      this.arguments.addAll(additionalTargets);
    }
    BuildEvent.Started started = postBuildStartedEvent(params);
    BuildRunResult result = ImmutableBuildRunResult.of(ExitCode.BUILD_ERROR, ImmutableList.of());
    try {
      result = executeBuildAndProcessResult(params, commandThreadManager, targetNodeSpecEnhancer);
    } catch (ActionGraphCreationException e) {
      params.getConsole().printBuildFailure(e.getMessage());
      result = ImmutableBuildRunResult.of(ExitCode.PARSE_ERROR, ImmutableList.of());
    } finally {
      params.getBuckEventBus().post(BuildEvent.finished(started, result.getExitCode()));
    }

    return result;
  }

  private BuildEvent.Started postBuildStartedEvent(CommandRunnerParams params) {
    BuildEvent.Started started = BuildEvent.started(getArguments());
    params.getBuckEventBus().post(started);
    return started;
  }

  GraphsAndBuildTargets createGraphsAndTargets(
      CommandRunnerParams params,
      ListeningExecutorService executorService,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger)
      throws ActionGraphCreationException, IOException, InterruptedException {
    TargetGraphAndBuildTargets unversionedTargetGraph =
        createUnversionedTargetGraph(params, executorService, targetNodeSpecEnhancer);

    Optional<TargetGraphAndBuildTargets> versionedTargetGraph = Optional.empty();
    try {
      if (params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()) {
        versionedTargetGraph = Optional.of(toVersionedTargetGraph(params, unversionedTargetGraph));
      }
    } catch (VersionException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    TargetGraphAndBuildTargets targetGraphForLocalBuild =
        ActionAndTargetGraphs.getTargetGraphForLocalBuild(
            unversionedTargetGraph, versionedTargetGraph);
    checkSingleBuildTargetSpecifiedForOutBuildMode(targetGraphForLocalBuild);
    ActionGraphAndBuilder actionGraph =
        createActionGraphAndResolver(params, targetGraphForLocalBuild, ruleKeyLogger);

    ImmutableSet<BuildTarget> buildTargets =
        getBuildTargets(
            params,
            actionGraph,
            targetGraphForLocalBuild,
            params.getTargetConfiguration(),
            justBuildTarget);

    ActionAndTargetGraphs actionAndTargetGraphs =
        ActionAndTargetGraphs.builder()
            .setUnversionedTargetGraph(unversionedTargetGraph)
            .setVersionedTargetGraph(versionedTargetGraph)
            .setActionGraphAndBuilder(actionGraph)
            .build();

    return ImmutableGraphsAndBuildTargets.of(actionAndTargetGraphs, buildTargets);
  }

  private void checkSingleBuildTargetSpecifiedForOutBuildMode(
      TargetGraphAndBuildTargets targetGraphAndBuildTargets) {
    // Ideally, we would error out of this before we build the entire graph, but it is possible
    // that `getArguments().size()` is 1 but `targetGraphAndBuildTargets.getBuildTargets().size()`
    // is greater than 1 if the lone argument is a wildcard build target that ends in "...".
    // As such, we have to get the result of createTargetGraph() before we can do this check.
    if (outputPathForSingleBuildTarget != null
        && targetGraphAndBuildTargets.getBuildTargets().size() != 1) {
      throw new CommandLineException(
          String.format(
              "When using %s you must specify exactly one build target, but you specified %s",
              OUT_LONG_ARG, targetGraphAndBuildTargets.getBuildTargets()));
    }
  }

  private BuildRunResult executeBuildAndProcessResult(
      CommandRunnerParams params,
      CommandThreadManager commandThreadManager,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer)
      throws Exception {
    ExitCode exitCode;
    GraphsAndBuildTargets graphsAndBuildTargets;
    if (isUsingDistributedBuild()) {
      return Objects.requireNonNull(getDistBuildCommandDelegate())
          .executeBuildAndProcessResult(params, commandThreadManager, this);
    } else {
      try (ThriftRuleKeyLogger ruleKeyLogger = createRuleKeyLogger().orElse(null)) {
        Optional<ThriftRuleKeyLogger> optionalRuleKeyLogger = Optional.ofNullable(ruleKeyLogger);
        graphsAndBuildTargets =
            createGraphsAndTargets(
                params,
                commandThreadManager.getListeningExecutorService(),
                targetNodeSpecEnhancer,
                optionalRuleKeyLogger);

        if (outputRuleDeps) {
          ActionGraphBuilder actionGraphBuilder =
              graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
          ImmutableSet<BuildTarget> buildTargets = graphsAndBuildTargets.getBuildTargets();
          Path outputPath = getSimulatorDir(params).resolve(ACTION_GRAPH_FILE_NAME);
          new ActionGraphSerializer(actionGraphBuilder, buildTargets, outputPath).serialize();
        }

        try (RuleKeyCacheScope<RuleKey> ruleKeyCacheScope =
            getDefaultRuleKeyCacheScope(
                params, graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder())) {
          exitCode =
              executeLocalBuild(
                  params,
                  graphsAndBuildTargets,
                  commandThreadManager.getWeightedListeningExecutorService(),
                  optionalRuleKeyLogger,
                  new NoOpRemoteBuildRuleCompletionWaiter(),
                  false,
                  Optional.empty(),
                  ruleKeyCacheScope,
                  lastBuild);
          if (exitCode == ExitCode.SUCCESS) {
            exitCode = processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
          }
        }
      }
    }

    return ImmutableBuildRunResult.of(exitCode, graphsAndBuildTargets.getBuildTargets());
  }

  /**
   * Create a {@link ThriftRuleKeyLogger} depending on whether {@link BuildCommand#ruleKeyLogPath}
   * is set or not
   */
  private Optional<ThriftRuleKeyLogger> createRuleKeyLogger() throws IOException {
    if (ruleKeyLogPath == null) {
      return Optional.empty();
    } else {
      return Optional.of(ThriftRuleKeyLogger.create(Paths.get(ruleKeyLogPath)));
    }
  }

  ExitCode processSuccessfulBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    if (params.getBuckConfig().getView(BuildBuckConfig.class).createBuildOutputSymLinksEnabled()) {
      symLinkBuildResults(params, graphsAndBuildTargets);
    }
    ActionAndTargetGraphs graphs = graphsAndBuildTargets.getGraphs();
    if (showOutput || showFullOutput || showJsonOutput || showFullJsonOutput || showRuleKey) {
      showOutputs(params, graphsAndBuildTargets, ruleKeyCacheScope);
    }
    if (outputPathForSingleBuildTarget != null) {
      BuildTarget loneTarget =
          Iterables.getOnlyElement(graphs.getTargetGraphForLocalBuild().getBuildTargets());
      BuildRule rule =
          graphs.getActionGraphAndBuilder().getActionGraphBuilder().getRule(loneTarget);
      if (!rule.outputFileCanBeCopied()) {
        params
            .getConsole()
            .printErrorText(
                String.format(
                    "%s does not have an output that is compatible with `buck build --out`",
                    loneTarget));
        return ExitCode.BUILD_ERROR;
      } else {
        SourcePath output =
            Preconditions.checkNotNull(
                rule.getSourcePathToOutput(),
                "%s specified a build target that does not have an output file: %s",
                OUT_LONG_ARG,
                loneTarget);

        ProjectFilesystem projectFilesystem = params.getCell().getFilesystem();
        SourcePathResolver pathResolver =
            graphs.getActionGraphAndBuilder().getActionGraphBuilder().getSourcePathResolver();

        Path outputPath;
        if (Files.isDirectory(outputPathForSingleBuildTarget)) {
          Path outputDir = outputPathForSingleBuildTarget.normalize();
          Path outputFilename = pathResolver.getAbsolutePath(output).getFileName();
          outputPath = outputDir.resolve(outputFilename);
        } else {
          outputPath = outputPathForSingleBuildTarget;
        }

        projectFilesystem.copyFile(pathResolver.getAbsolutePath(output), outputPath);
      }
    }
    return ExitCode.SUCCESS;
  }

  private void symLinkBuildRuleResult(
      SourcePathResolver pathResolver,
      BuckConfig buckConfig,
      Path lastOutputDirPath,
      BuildRule rule)
      throws IOException {
    Optional<Path> outputPath =
        TargetsCommand.getUserFacingOutputPath(
            pathResolver, rule, buckConfig.getView(BuildBuckConfig.class).getBuckOutCompatLink());
    if (outputPath.isPresent()) {
      Path absolutePath = outputPath.get();
      Path destPath = lastOutputDirPath.relativize(absolutePath);
      Path linkPath = lastOutputDirPath.resolve(absolutePath.getFileName());
      // Don't overwrite existing symlink in case there are duplicate names.
      if (!Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
        ProjectFilesystem projectFilesystem = rule.getProjectFilesystem();
        projectFilesystem.createSymLink(linkPath, destPath, false);
      }
    }
  }

  private void symLinkBuildResults(
      CommandRunnerParams params, GraphsAndBuildTargets graphsAndBuildTargets) throws IOException {
    // Clean up last buck-out/last.
    Path lastOutputDirPath =
        params.getCell().getFilesystem().getBuckPaths().getLastOutputDir().toAbsolutePath();
    MostFiles.deleteRecursivelyIfExists(lastOutputDirPath);
    Files.createDirectories(lastOutputDirPath);

    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();

    for (BuildTarget buildTarget : graphsAndBuildTargets.getBuildTargets()) {
      BuildRule rule = graphBuilder.requireRule(buildTarget);
      // If it's an apple bundle, we'd like to also link the dSYM file over here.
      if (rule instanceof AppleBundle) {
        AppleBundle bundle = (AppleBundle) rule;
        Optional<AppleDsym> dsym = bundle.getAppleDsym();
        if (dsym.isPresent()) {
          symLinkBuildRuleResult(
              pathResolver, params.getBuckConfig(), lastOutputDirPath, dsym.get());
        }
      }
      symLinkBuildRuleResult(pathResolver, params.getBuckConfig(), lastOutputDirPath, rule);
    }
  }

  private void showOutputs(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope)
      throws IOException {
    TreeMap<String, String> sortedJsonOutputs = new TreeMap<>();
    Optional<DefaultRuleKeyFactory> ruleKeyFactory = Optional.empty();
    ActionGraphBuilder graphBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder().getActionGraphBuilder();
    if (showRuleKey) {
      RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(params.getRuleKeyConfiguration());
      ruleKeyFactory =
          Optional.of(
              new DefaultRuleKeyFactory(
                  fieldLoader,
                  params.getFileHashCache(),
                  graphBuilder,
                  ruleKeyCacheScope.getCache(),
                  Optional.empty()));
    }
    for (BuildTarget buildTarget : graphsAndBuildTargets.getBuildTargets()) {
      BuildRule rule = graphBuilder.requireRule(buildTarget);
      Optional<Path> outputPath =
          TargetsCommand.getUserFacingOutputPath(
                  graphBuilder.getSourcePathResolver(),
                  rule,
                  params.getBuckConfig().getView(BuildBuckConfig.class).getBuckOutCompatLink())
              .map(
                  path ->
                      showFullOutput || showFullJsonOutput
                          ? path
                          : params.getCell().getFilesystem().relativize(path));

      params.getConsole().getStdOut().flush();
      if (showJsonOutput || showFullJsonOutput) {
        sortedJsonOutputs.put(
            rule.getFullyQualifiedName(), outputPath.map(Object::toString).orElse(""));
      } else {
        params
            .getConsole()
            .getStdOut()
            .printf(
                "%s%s%s\n",
                rule.getFullyQualifiedName(),
                showRuleKey ? " " + ruleKeyFactory.get().build(rule) : "",
                showOutput || showFullOutput
                    ? " " + outputPath.map(Object::toString).orElse("")
                    : "");
      }
    }

    if (showJsonOutput || showFullJsonOutput) {
      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, sortedJsonOutputs);
      String output = stringWriter.getBuffer().toString();
      params.getConsole().getStdOut().println(output);
    }
  }

  private TargetGraphAndBuildTargets createUnversionedTargetGraph(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      Function<ImmutableList<TargetNodeSpec>, ImmutableList<TargetNodeSpec>> targetNodeSpecEnhancer)
      throws IOException, InterruptedException, ActionGraphCreationException {
    // Parse the build files to create a ActionGraph.
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      return params
          .getParser()
          .buildTargetGraphWithoutConfigurationTargets(
              createParsingContext(params.getCell(), executor)
                  .withSpeculativeParsing(SpeculativeParsing.ENABLED)
                  .withApplyDefaultFlavorsMode(parserConfig.getDefaultFlavorsMode()),
              targetNodeSpecEnhancer.apply(
                  parseArgumentsAsTargetNodeSpecs(
                      params.getCell(), params.getBuckConfig(), getArguments())),
              params.getTargetConfiguration());
    } catch (BuildTargetException e) {
      throw new ActionGraphCreationException(MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  private static ActionGraphAndBuilder createActionGraphAndResolver(
      CommandRunnerParams params,
      TargetGraphAndBuildTargets targetGraphAndBuildTargets,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    return params
        .getActionGraphProvider()
        .getActionGraph(
            new DefaultTargetNodeToBuildRuleTransformer(),
            targetGraphAndBuildTargets.getTargetGraph(),
            ruleKeyLogger);
  }

  private static ImmutableSet<BuildTarget> getBuildTargets(
      CommandRunnerParams params,
      ActionGraphAndBuilder actionGraphAndBuilder,
      TargetGraphAndBuildTargets targetGraph,
      TargetConfiguration targetConfiguration,
      @Nullable String justBuildTarget)
      throws ActionGraphCreationException {
    ImmutableSet<BuildTarget> buildTargets = targetGraph.getBuildTargets();
    if (justBuildTarget == null) {
      return buildTargets;
    }

    // If the user specified an explicit build target, use that.
    BuildTarget explicitTarget =
        params
            .getUnconfiguredBuildTargetFactory()
            .create(params.getCell().getCellPathResolver(), justBuildTarget)
            .configure(targetConfiguration);
    Iterable<BuildRule> actionGraphRules =
        Objects.requireNonNull(actionGraphAndBuilder.getActionGraph().getNodes());
    ImmutableSet<BuildTarget> actionGraphTargets =
        ImmutableSet.copyOf(Iterables.transform(actionGraphRules, BuildRule::getBuildTarget));
    if (!actionGraphTargets.contains(explicitTarget)) {
      throw new ActionGraphCreationException(
          "Targets specified via `--just-build` must be a subset of action graph.");
    }
    return ImmutableSet.of(explicitTarget);
  }

  protected ExitCode executeLocalBuild(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executor,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
      boolean isDownloadHeavyBuild,
      Optional<CountDownLatch> initializeBuildLatch,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope,
      AtomicReference<Build> buildReference)
      throws Exception {

    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    boolean whitelistedForRemoteExecution =
        params
            .getBuckConfig()
            .getView(RemoteExecutionConfig.class)
            .isBuildWhitelistedForRemoteExecution(
                params.getBuildEnvironmentDescription().getUser(), getArguments());
    LocalBuildExecutor builder =
        new LocalBuildExecutor(
            params.createBuilderArgs(),
            getExecutionContext(),
            actionGraphAndBuilder,
            new LocalCachingBuildEngineDelegate(params.getFileHashCache()),
            executor,
            isKeepGoing(),
            isUsingDistributedBuild(),
            isDownloadHeavyBuild,
            ruleKeyCacheScope,
            getBuildEngineMode(),
            ruleKeyLogger,
            remoteBuildRuleCompletionWaiter,
            params.getMetadataProvider(),
            params.getUnconfiguredBuildTargetFactory(),
            params.getTargetConfiguration(),
            params.getTargetConfigurationSerializer(),
            whitelistedForRemoteExecution);
    // TODO(buck_team): use try-with-resources instead
    try {
      buildReference.set(builder.getBuild());
      // TODO(alisdair): ensure that all Stampede local builds re-use same calculator
      localRuleKeyCalculator.set(builder.getCachingBuildEngine().getRuleKeyCalculator());

      // Signal to other threads that lastBuild has now been set.
      initializeBuildLatch.ifPresent(CountDownLatch::countDown);

      Iterable<BuildTarget> targets =
          FluentIterable.concat(
              graphsAndBuildTargets.getBuildTargets(),
              getAdditionalTargetsToBuild(graphsAndBuildTargets));

      return builder.buildTargets(targets, getPathToBuildReport(params.getBuckConfig()));
    } finally {
      builder.shutdown();
    }
  }

  RuleKeyCacheScope<RuleKey> getDefaultRuleKeyCacheScope(
      CommandRunnerParams params, ActionGraphAndBuilder actionGraphAndBuilder) {
    return getDefaultRuleKeyCacheScope(
        params,
        new RuleKeyCacheRecycler.SettingsAffectingCache(
            params.getBuckConfig().getView(BuildBuckConfig.class).getKeySeed(),
            actionGraphAndBuilder.getActionGraph()));
  }

  @Override
  protected ExecutionContext.Builder getExecutionContextBuilder(CommandRunnerParams params) {
    return super.getExecutionContextBuilder(params)
        .setTargetDevice(Optional.empty())
        .setCodeCoverageEnabled(isCodeCoverageEnabled())
        .setDebugEnabled(isDebugEnabled())
        .setShouldReportAbsolutePaths(shouldReportAbsolutePaths());
  }

  @SuppressWarnings("unused")
  protected Iterable<BuildTarget> getAdditionalTargetsToBuild(
      GraphsAndBuildTargets graphsAndBuildTargets) {
    return ImmutableList.of();
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isSourceControlStatsGatheringEnabled() {
    return true;
  }

  Build getBuild() {
    return Objects.requireNonNull(lastBuild.get());
  }

  @Override
  public String getShortDescription() {
    return "builds the specified target";
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners(
      Map<ExecutorPool, ListeningExecutorService> executorPool,
      ScheduledExecutorService scheduledExecutorService) {
    if (isUsingDistributedBuild()) {
      return Objects.requireNonNull(getDistBuildCommandDelegate()).getEventListeners();
    } else {
      return ImmutableList.of();
    }
  }

  public static class ActionGraphCreationException extends Exception {
    public ActionGraphCreationException(String message) {
      super(message);
    }
  }

  @Override
  public boolean performsBuild() {
    return true;
  }

  @Immutable(builder = false, copy = false)
  interface GraphsAndBuildTargets {
    @Value.Parameter
    ActionAndTargetGraphs getGraphs();

    @Value.Parameter
    ImmutableSet<BuildTarget> getBuildTargets();
  }

  @Immutable(builder = false, copy = false)
  interface BuildRunResult {
    @Value.Parameter
    ExitCode getExitCode();

    @Value.Parameter
    ImmutableSet<BuildTarget> getBuildTargets();
  }
}
