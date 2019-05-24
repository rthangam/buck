/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.CxxPrepareForLinkStep;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class GoBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, HasRuntimeDeps {

  @AddToRuleKey private final Tool linker;
  @AddToRuleKey private final Linker cxxLinker;
  @AddToRuleKey private final ImmutableList<String> linkerFlags;
  @AddToRuleKey private final ImmutableList<Arg> cxxLinkerArgs;
  @AddToRuleKey private final GoLinkStep.LinkMode linkMode;
  @AddToRuleKey private final GoPlatform platform;

  private final Path output;
  private final GoCompile mainObject;
  private final SymlinkTree linkTree;
  private final ImmutableSortedSet<SourcePath> resources;

  public GoBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> resources,
      SymlinkTree linkTree,
      GoCompile mainObject,
      Tool linker,
      Linker cxxLinker,
      GoLinkStep.LinkMode linkMode,
      ImmutableList<String> linkerFlags,
      ImmutableList<Arg> cxxLinkerArgs,
      GoPlatform platform) {
    super(buildTarget, projectFilesystem, params);
    this.cxxLinker = cxxLinker;
    this.cxxLinkerArgs = cxxLinkerArgs;
    this.resources = resources;
    this.linker = linker;
    this.linkTree = linkTree;
    this.mainObject = mainObject;
    this.platform = platform;

    String outputFormat = "%s/" + buildTarget.getShortName();
    if (platform.getGoOs() == GoOs.WINDOWS) {
      outputFormat = outputFormat + ".exe";
    }
    this.output = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, outputFormat);

    this.linkerFlags = linkerFlags;
    this.linkMode = linkMode;
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder().addArg(SourcePathArg.of(getSourcePathToOutput())).build();
  }

  private ImmutableMap<String, String> getEnvironment(BuildContext context) {
    ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();

    if (linkMode == GoLinkStep.LinkMode.EXTERNAL) {
      environment.putAll(cxxLinker.getEnvironment(context.getSourcePathResolver()));
    }
    environment.putAll(linker.getEnvironment(context.getSourcePathResolver()));

    return environment.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifact(output);

    SourcePathResolver resolver = context.getSourcePathResolver();
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    // copy resources to target directory
    for (SourcePath resource : resources) {
      // sourcePathName is the name of the resource as found in BUCK file:
      // testdata/level2
      String sourcePathName = resolver.getSourcePathName(getBuildTarget(), resource);
      // outputResourcePath is the full path to buck-out/gen/targetdir...
      // buck-out/gen/test-with-resources-2directory-2resources#test-main/testdata/level2
      Path outputResourcePath = output.getParent().resolve(sourcePathName);
      buildableContext.recordArtifact(outputResourcePath);
      if (Files.isDirectory(resolver.getAbsolutePath(resource))) {
        steps.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    outputResourcePath.getParent())));
        steps.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                resolver.getRelativePath(resource),
                outputResourcePath.getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      } else {
        steps.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    outputResourcePath.getParent())));
        steps.add(
            CopyStep.forFile(
                getProjectFilesystem(), resolver.getRelativePath(resource), outputResourcePath));
      }
    }

    // cxxLinkerArgs comes from cgo rules and are reuqired for cxx deps linking
    ImmutableList.Builder<String> externalLinkerFlags = ImmutableList.builder();
    if (linkMode == GoLinkStep.LinkMode.EXTERNAL) {
      Path argFilePath =
          getProjectFilesystem()
              .getRootPath()
              .resolve(
                  BuildTargetPaths.getScratchPath(
                      getProjectFilesystem(), getBuildTarget(), "%s.argsfile"));
      Path fileListPath =
          getProjectFilesystem()
              .getRootPath()
              .resolve(
                  BuildTargetPaths.getScratchPath(
                      getProjectFilesystem(), getBuildTarget(), "%s__filelist.txt"));
      steps.addAll(
          CxxPrepareForLinkStep.create(
              argFilePath,
              fileListPath,
              cxxLinker.fileList(fileListPath),
              output,
              cxxLinkerArgs,
              cxxLinker,
              getBuildTarget().getCellPath(),
              resolver));
      externalLinkerFlags.add(String.format("@%s", argFilePath));
    }

    steps.add(
        new GoLinkStep(
            getProjectFilesystem().getRootPath(),
            getEnvironment(context),
            cxxLinker.getCommandPrefix(resolver),
            linker.getCommandPrefix(resolver),
            linkerFlags,
            externalLinkerFlags.build(),
            ImmutableList.of(linkTree.getRoot()),
            platform,
            resolver.getRelativePath(mainObject.getSourcePathToOutput()),
            GoLinkStep.BuildMode.EXECUTABLE,
            linkMode,
            output));
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    // For shared-style linked binaries, we need to ensure that the symlink tree and its
    // dependencies are available, or we will get a runtime linking error
    return getDeclaredDeps().stream().map(BuildRule::getBuildTarget);
  }
}
