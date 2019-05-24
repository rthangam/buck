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

package com.facebook.buck.android;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An object that represents a collection of Android NDK source code.
 *
 * <p>Suppose this were a rule defined in <code>src/com/facebook/feed/jni/BUCK</code>:
 *
 * <pre>
 * ndk_library(
 *   name = 'feed-jni',
 *   deps = [],
 *   flags = ["NDK_DEBUG=1", "V=1"],
 * )
 * </pre>
 */
public class NdkLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements NativeLibraryBuildRule, AndroidPackageable {

  private final AndroidNdk androidNdk;

  /** @see NativeLibraryBuildRule#isAsset() */
  @AddToRuleKey private final boolean isAsset;

  /** The directory containing the Android.mk file to use. This value includes a trailing slash. */
  private final Path root;

  private final Path makefile;
  private final String makefileContents;
  private final Path buildArtifactsDirectory;
  private final Path genDirectory;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> sources;

  @AddToRuleKey private final ImmutableList<Arg> flags;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final String ndkVersion;

  protected NdkLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidNdk androidNdk,
      BuildRuleParams params,
      Path makefile,
      String makefileContents,
      Set<SourcePath> sources,
      ImmutableList<Arg> flags,
      boolean isAsset,
      String ndkVersion) {
    super(buildTarget, projectFilesystem, params);
    this.androidNdk = androidNdk;
    this.isAsset = isAsset;

    this.root = buildTarget.getBasePath();
    this.makefile = Objects.requireNonNull(makefile);
    this.makefileContents = makefileContents;
    this.buildArtifactsDirectory = getBuildArtifactsDirectory(buildTarget, true /* isScratchDir */);
    this.genDirectory = getBuildArtifactsDirectory(buildTarget, false /* isScratchDir */);

    Preconditions.checkArgument(
        !sources.isEmpty(), "Must include at least one file (Android.mk?) in ndk_library rule");
    this.sources = ImmutableSortedSet.copyOf(sources);
    this.flags = flags;

    this.ndkVersion = ndkVersion;
  }

  @Override
  public boolean isAsset() {
    return isAsset;
  }

  @Override
  public Path getLibraryPath() {
    return genDirectory;
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    // An ndk_library() does not have a "primary output" at this time.
    return null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // .so files are written to the libs/ subdirectory of the output directory.
    // All of them should be recorded via the BuildableContext.
    Path binDirectory = buildArtifactsDirectory.resolve("libs");
    steps.add(
        RmStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), makefile)));
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), makefile.getParent())));
    steps.add(new WriteFileStep(getProjectFilesystem(), makefileContents, makefile, false));
    steps.add(
        new NdkBuildStep(
            getProjectFilesystem(),
            androidNdk,
            root,
            makefile,
            buildArtifactsDirectory,
            binDirectory,
            Arg.stringify(flags, context.getSourcePathResolver())));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), genDirectory)));
    steps.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            binDirectory,
            genDirectory,
            CopyStep.DirectoryMode.CONTENTS_ONLY));

    buildableContext.recordArtifact(genDirectory);
    // Some tools need to inspect .so files whose symbols haven't been stripped, so cache these too.
    // However, the intermediate object files are huge and we have no interest in them, so filter
    // them out.
    steps.add(
        new AbstractExecutionStep("cache_unstripped_so") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            Set<Path> unstrippedSharedObjs =
                getProjectFilesystem()
                    .getFilesUnderPath(
                        buildArtifactsDirectory, input -> input.toString().endsWith(".so"));
            for (Path path : unstrippedSharedObjs) {
              buildableContext.recordArtifact(path);
            }
            return StepExecutionResults.SUCCESS;
          }
        });

    return steps.build();
  }

  /**
   * @param isScratchDir true if this should be the "working directory" where a build rule may write
   *     intermediate files when computing its output. false if this should be the gen/ directory
   *     where the "official" outputs of the build rule should be written. Files of the latter type
   *     can be referenced via a {@link BuildTargetSourcePath} or somesuch.
   */
  private Path getBuildArtifactsDirectory(BuildTarget target, boolean isScratchDir) {
    return isScratchDir
        ? BuildTargetPaths.getScratchPath(getProjectFilesystem(), target, "__lib%s")
        : BuildTargetPaths.getGenPath(getProjectFilesystem(), target, "__lib%s");
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return AndroidPackageableCollector.getPackageableRules(getBuildDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (isAsset) {
      collector.addNativeLibAssetsDirectory(
          getBuildTarget(), ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibraryPath()));
    } else {
      collector.addNativeLibsDirectory(
          getBuildTarget(), ExplicitBuildTargetSourcePath.of(getBuildTarget(), getLibraryPath()));
    }
  }
}
