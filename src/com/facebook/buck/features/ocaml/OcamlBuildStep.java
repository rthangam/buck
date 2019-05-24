/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** A step that preprocesses, compiles, and assembles OCaml sources. */
public class OcamlBuildStep implements Step {

  private final BuildContext buildContext;
  private final ProjectFilesystem filesystem;
  private final OcamlBuildContext ocamlContext;
  private final BuildTarget buildTarget;
  private final ImmutableMap<String, String> cCompilerEnvironment;
  private final ImmutableList<String> cCompiler;
  private final ImmutableMap<String, String> cxxCompilerEnvironment;
  private final ImmutableList<String> cxxCompiler;
  private final boolean bytecodeOnly;

  private final boolean hasGeneratedSources;
  private final OcamlDepToolStep depToolStep;

  public OcamlBuildStep(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OcamlBuildContext ocamlContext,
      BuildTarget buildTarget,
      ImmutableMap<String, String> cCompilerEnvironment,
      ImmutableList<String> cCompiler,
      ImmutableMap<String, String> cxxCompilerEnvironment,
      ImmutableList<String> cxxCompiler,
      boolean bytecodeOnly) {
    this.buildContext = buildContext;
    this.filesystem = filesystem;
    this.ocamlContext = ocamlContext;
    this.buildTarget = buildTarget;
    this.cCompilerEnvironment = cCompilerEnvironment;
    this.cCompiler = cCompiler;
    this.cxxCompilerEnvironment = cxxCompilerEnvironment;
    this.cxxCompiler = cxxCompiler;
    this.bytecodeOnly = bytecodeOnly;

    hasGeneratedSources =
        ocamlContext.getLexInput().size() > 0 || ocamlContext.getYaccInput().size() > 0;

    ImmutableList<String> ocamlDepFlags =
        ImmutableList.<String>builder()
            .addAll(
                this.ocamlContext.getIncludeFlags(/* isBytecode */ false, /* excludeDeps */ true))
            .addAll(this.ocamlContext.getOcamlDepFlags())
            .build();

    this.depToolStep =
        new OcamlDepToolStep(
            filesystem.getRootPath(),
            this.ocamlContext.getSourcePathResolver(),
            this.ocamlContext.getOcamlDepTool().get(),
            ocamlContext.getMLInput(),
            ocamlDepFlags);
  }

  @Override
  public String getShortName() {
    return "OCaml compile";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return depToolStep.getDescription(context);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (hasGeneratedSources) {
      StepExecutionResult genExecutionResult = generateSources(context, filesystem.getRootPath());
      if (!genExecutionResult.isSuccess()) {
        return genExecutionResult;
      }
    }

    StepExecutionResult depToolExecutionResult = depToolStep.execute(context);
    if (!depToolExecutionResult.isSuccess()) {
      return depToolExecutionResult;
    }

    // OCaml requires module A to be present in command line to ocamlopt or ocamlc before
    // module B if B depends on A. In OCaml circular dependencies are prohibited, so all
    // dependency relations among modules form DAG. Topologically sorting this graph satisfies the
    // requirement.
    //
    // To get the DAG we launch ocamldep tool which provides the direct dependency information, like
    // module A depends on modules B, C, D.
    ImmutableList<Path> sortedInput =
        sortDependency(
            depToolStep.getStdout(),
            ocamlContext.getSourcePathResolver().getAllAbsolutePaths(ocamlContext.getMLInput()));

    ImmutableList.Builder<Path> nativeLinkerInputs = ImmutableList.builder();

    if (!bytecodeOnly) {
      StepExecutionResult mlCompileNativeExecutionResult =
          executeMLNativeCompilation(
              context, filesystem.getRootPath(), sortedInput, nativeLinkerInputs);
      if (!mlCompileNativeExecutionResult.isSuccess()) {
        return mlCompileNativeExecutionResult;
      }
    }

    ImmutableList.Builder<Path> bytecodeLinkerInputs = ImmutableList.builder();
    StepExecutionResult mlCompileBytecodeExecutionResult =
        executeMLBytecodeCompilation(
            context, filesystem.getRootPath(), sortedInput, bytecodeLinkerInputs);
    if (!mlCompileBytecodeExecutionResult.isSuccess()) {
      return mlCompileBytecodeExecutionResult;
    }

    ImmutableList.Builder<Path> cLinkerInputs = ImmutableList.builder();
    StepExecutionResult cCompileExecutionResult = executeCCompilation(context, cLinkerInputs);
    if (!cCompileExecutionResult.isSuccess()) {
      return cCompileExecutionResult;
    }

    ImmutableList<Path> cObjects = cLinkerInputs.build();

    if (!bytecodeOnly) {
      nativeLinkerInputs.addAll(cObjects);
      StepExecutionResult nativeLinkExecutionResult =
          executeNativeLinking(context, nativeLinkerInputs.build());
      if (!nativeLinkExecutionResult.isSuccess()) {
        return nativeLinkExecutionResult;
      }
    }

    bytecodeLinkerInputs.addAll(cObjects);
    StepExecutionResult bytecodeLinkExecutionResult =
        executeBytecodeLinking(context, bytecodeLinkerInputs.build());
    if (!bytecodeLinkExecutionResult.isSuccess()) {
      return bytecodeLinkExecutionResult;
    }

    if (!ocamlContext.isLibrary()) {
      Step debugLauncher =
          new OcamlDebugLauncherStep(
              filesystem,
              getResolver(),
              new OcamlDebugLauncherStep.Args(
                  ocamlContext.getOcamlDebug().get(),
                  ocamlContext.getBytecodeOutput(),
                  ocamlContext.getTransitiveBytecodeIncludes(),
                  ocamlContext.getBytecodeIncludeFlags()));
      return debugLauncher.execute(context);
    } else {
      return StepExecutionResults.SUCCESS;
    }
  }

  private StepExecutionResult executeCCompilation(
      ExecutionContext context, ImmutableList.Builder<Path> linkerInputs)
      throws IOException, InterruptedException {

    ImmutableList.Builder<Arg> cCompileFlags = ImmutableList.builder();
    cCompileFlags.addAll(ocamlContext.getCCompileFlags());
    cCompileFlags.addAll(StringArg.from(ocamlContext.getCommonCFlags()));

    CxxPreprocessorInput cxxPreprocessorInput = ocamlContext.getCxxPreprocessorInput();

    for (SourcePath cSrc : ocamlContext.getCInput()) {
      Path outputPath = ocamlContext.getCOutput(getResolver().getAbsolutePath(cSrc));
      linkerInputs.add(outputPath);
      Step compileStep =
          new OcamlCCompileStep(
              getResolver(),
              filesystem,
              new OcamlCCompileStep.Args(
                  buildTarget,
                  cCompilerEnvironment,
                  cCompiler,
                  ocamlContext.getOcamlCompiler().get(),
                  ocamlContext.getOcamlInteropIncludesDir(),
                  outputPath,
                  cSrc,
                  cCompileFlags.build(),
                  cxxPreprocessorInput.getIncludes()));
      StepExecutionResult compileExecutionResult = compileStep.execute(context);
      if (!compileExecutionResult.isSuccess()) {
        return compileExecutionResult;
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  private StepExecutionResult executeNativeLinking(
      ExecutionContext context, ImmutableList<Path> linkerInputs)
      throws IOException, InterruptedException {

    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(StringArg.from(ocamlContext.getCommonCLinkerFlags()));

    OcamlLinkStep linkStep =
        OcamlLinkStep.create(
            filesystem,
            cxxCompilerEnvironment,
            cxxCompiler,
            ocamlContext.getOcamlCompiler().get().getCommandPrefix(getResolver()),
            flags.build(),
            ocamlContext.getOcamlInteropIncludesDir(),
            ocamlContext.getNativeOutput(),
            OcamlUtil.makeLinkerArgFilePath(filesystem, buildTarget),
            ocamlContext.getNativeLinkableInput().getArgs(),
            ocamlContext.getCLinkableInput().getArgs(),
            linkerInputs,
            ocamlContext.isLibrary(),
            /* isBytecode */ false,
            getResolver());
    return linkStep.execute(context);
  }

  private StepExecutionResult executeBytecodeLinking(
      ExecutionContext context, ImmutableList<Path> linkerInputs)
      throws IOException, InterruptedException {

    ImmutableList.Builder<Arg> flags = ImmutableList.builder();
    flags.addAll(ocamlContext.getFlags());
    flags.addAll(StringArg.from(ocamlContext.getCommonCLinkerFlags()));

    OcamlLinkStep linkStep =
        OcamlLinkStep.create(
            filesystem,
            cxxCompilerEnvironment,
            cxxCompiler,
            ocamlContext.getOcamlBytecodeCompiler().get().getCommandPrefix(getResolver()),
            flags.build(),
            ocamlContext.getOcamlInteropIncludesDir(),
            ocamlContext.getBytecodeOutput(),
            OcamlUtil.makeLinkerArgFilePath(filesystem, buildTarget),
            ocamlContext.getBytecodeLinkableInput().getArgs(),
            ocamlContext.getCLinkableInput().getArgs(),
            linkerInputs,
            ocamlContext.isLibrary(),
            /* isBytecode */ true,
            getResolver());
    return linkStep.execute(context);
  }

  private ImmutableList<Arg> getCompileFlags(boolean isBytecode, boolean excludeDeps) {
    String output =
        isBytecode
            ? ocamlContext.getCompileBytecodeOutputDir().toString()
            : ocamlContext.getCompileNativeOutputDir().toString();
    ImmutableList.Builder<Arg> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(
        StringArg.from(ocamlContext.getIncludeFlags(isBytecode, /* excludeDeps */ excludeDeps)));
    flagBuilder.addAll(ocamlContext.getFlags());
    flagBuilder.add(StringArg.of(OcamlCompilables.OCAML_INCLUDE_FLAG), StringArg.of(output));
    return flagBuilder.build();
  }

  private StepExecutionResult executeMLNativeCompilation(
      ExecutionContext context,
      Path workingDirectory,
      ImmutableList<Path> sortedInput,
      ImmutableList.Builder<Path> linkerInputs)
      throws IOException, InterruptedException {
    for (Step step :
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                filesystem,
                ocamlContext.getCompileNativeOutputDir()))) {
      StepExecutionResult mkDirExecutionResult = step.execute(context);
      if (!mkDirExecutionResult.isSuccess()) {
        return mkDirExecutionResult;
      }
    }
    for (Path inputOutput : sortedInput) {
      String inputFileName = inputOutput.getFileName().toString();
      String outputFileName =
          inputFileName
              .replaceFirst(OcamlCompilables.OCAML_ML_REGEX, OcamlCompilables.OCAML_CMX)
              .replaceFirst(OcamlCompilables.OCAML_RE_REGEX, OcamlCompilables.OCAML_CMX)
              .replaceFirst(OcamlCompilables.OCAML_MLI_REGEX, OcamlCompilables.OCAML_CMI)
              .replaceFirst(OcamlCompilables.OCAML_REI_REGEX, OcamlCompilables.OCAML_CMI);
      Path outputPath = ocamlContext.getCompileNativeOutputDir().resolve(outputFileName);
      if (!outputFileName.endsWith(OcamlCompilables.OCAML_CMI)) {
        linkerInputs.add(outputPath);
      }
      ImmutableList<Arg> compileFlags =
          getCompileFlags(/* isBytecode */ false, /* excludeDeps */ false);
      Step compileStep =
          new OcamlMLCompileStep(
              workingDirectory,
              getResolver(),
              new OcamlMLCompileStep.Args(
                  cCompilerEnvironment,
                  cCompiler,
                  ocamlContext.getOcamlCompiler().get(),
                  ocamlContext.getOcamlInteropIncludesDir(),
                  outputPath,
                  inputOutput,
                  compileFlags));
      StepExecutionResult compileExecutionResult = compileStep.execute(context);
      if (!compileExecutionResult.isSuccess()) {
        return compileExecutionResult;
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  private StepExecutionResult executeMLBytecodeCompilation(
      ExecutionContext context,
      Path workingDirectory,
      ImmutableList<Path> sortedInput,
      ImmutableList.Builder<Path> linkerInputs)
      throws IOException, InterruptedException {
    for (Step step :
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                filesystem,
                ocamlContext.getCompileBytecodeOutputDir()))) {
      StepExecutionResult mkDirExecutionResult = step.execute(context);
      if (!mkDirExecutionResult.isSuccess()) {
        return mkDirExecutionResult;
      }
    }
    for (Path inputOutput : sortedInput) {
      String inputFileName = inputOutput.getFileName().toString();
      String outputFileName =
          inputFileName
              .replaceFirst(OcamlCompilables.OCAML_ML_REGEX, OcamlCompilables.OCAML_CMO)
              .replaceFirst(OcamlCompilables.OCAML_RE_REGEX, OcamlCompilables.OCAML_CMO)
              .replaceFirst(OcamlCompilables.OCAML_MLI_REGEX, OcamlCompilables.OCAML_CMI)
              .replaceFirst(OcamlCompilables.OCAML_REI_REGEX, OcamlCompilables.OCAML_CMI);
      Path outputPath = ocamlContext.getCompileBytecodeOutputDir().resolve(outputFileName);
      if (!outputFileName.endsWith(OcamlCompilables.OCAML_CMI)) {
        linkerInputs.add(outputPath);
      }
      ImmutableList<Arg> compileFlags =
          getCompileFlags(/* isBytecode */ true, /* excludeDeps */ false);
      Step compileBytecodeStep =
          new OcamlMLCompileStep(
              workingDirectory,
              getResolver(),
              new OcamlMLCompileStep.Args(
                  cCompilerEnvironment,
                  cCompiler,
                  ocamlContext.getOcamlBytecodeCompiler().get(),
                  ocamlContext.getOcamlInteropIncludesDir(),
                  outputPath,
                  inputOutput,
                  compileFlags));
      StepExecutionResult compileExecutionResult = compileBytecodeStep.execute(context);
      if (!compileExecutionResult.isSuccess()) {
        return compileExecutionResult;
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  private StepExecutionResult generateSources(ExecutionContext context, Path workingDirectory)
      throws IOException, InterruptedException {
    for (Step step :
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                filesystem,
                ocamlContext.getGeneratedSourceDir()))) {
      StepExecutionResult mkDirExecutionResult = step.execute(context);
      if (!mkDirExecutionResult.isSuccess()) {
        return mkDirExecutionResult;
      }
    }
    for (SourcePath yaccSource : ocamlContext.getYaccInput()) {
      SourcePath output = ocamlContext.getYaccOutput(ImmutableSet.of(yaccSource)).get(0);
      OcamlYaccStep yaccStep =
          new OcamlYaccStep(
              workingDirectory,
              getResolver(),
              new OcamlYaccStep.Args(
                  ocamlContext.getYaccCompiler().get(),
                  getResolver().getAbsolutePath(output),
                  getResolver().getAbsolutePath(yaccSource)));
      StepExecutionResult yaccExecutionResult = yaccStep.execute(context);
      if (!yaccExecutionResult.isSuccess()) {
        return yaccExecutionResult;
      }
    }
    for (SourcePath lexSource : ocamlContext.getLexInput()) {
      SourcePath output = ocamlContext.getLexOutput(ImmutableSet.of(lexSource)).get(0);
      OcamlLexStep lexStep =
          new OcamlLexStep(
              workingDirectory,
              getResolver(),
              new OcamlLexStep.Args(
                  ocamlContext.getLexCompiler().get(),
                  getResolver().getAbsolutePath(output),
                  getResolver().getAbsolutePath(lexSource)));
      StepExecutionResult lexExecutionResult = lexStep.execute(context);
      if (!lexExecutionResult.isSuccess()) {
        return lexExecutionResult;
      }
    }
    return StepExecutionResults.SUCCESS;
  }

  private ImmutableList<Path> sortDependency(
      String depOutput, ImmutableSet<Path> mlInput) { // NOPMD doesn't understand method reference
    OcamlDependencyGraphGenerator graphGenerator = new OcamlDependencyGraphGenerator();
    return FluentIterable.from(graphGenerator.generate(depOutput))
        .transform(Paths::get)
        // The output of generate needs to be filtered as .cmo dependencies
        // are generated as both .ml and .re files.
        .filter(mlInput::contains)
        .toList();
  }

  private SourcePathResolver getResolver() {
    return buildContext.getSourcePathResolver();
  }
}
