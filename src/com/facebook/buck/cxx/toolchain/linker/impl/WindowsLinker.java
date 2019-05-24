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

package com.facebook.buck.cxx.toolchain.linker.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.linker.HasImportLibrary;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.file.FileScrubber;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.HasSourcePath;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * A specialization of {@link Linker} containing information specific to the Windows implementation.
 */
public class WindowsLinker extends DelegatingTool implements Linker, HasImportLibrary {

  private ExtraOutputsDeriver WINDOWS_EXTRA_OUTPUTS_DERIVER =
      new ExtraOutputsDeriver() {
        @Override
        public ImmutableMap<String, Path> deriveExtraOutputsFromArgs(
            ImmutableList<String> linkerArgs, Path output) {

          ImmutableMap.Builder<String, Path> builder = new ImmutableMap.Builder<String, Path>();

          // A .pdb is generated if any /DEBUG option is specified, which isn't /DEBUG:NONE.
          // Buck realistically only support /DEBUG, which is the same as /DEBUG:FULL, but lld-link
          // has other options including /DEBUG:GHASH, so we have to be more careful checking here.
          boolean isPdbGenerated =
              linkerArgs.stream()
                  .anyMatch(arg -> arg.startsWith("/DEBUG") && !arg.equals("/DEBUG:NONE"));
          if (isPdbGenerated) {
            String pdbFilename = MorePaths.getNameWithoutExtension(output) + ".pdb";
            Path pdbOutput = output.getParent().resolve(pdbFilename);
            builder.put("pdb", pdbOutput);
          }
          // An implib is generated if we are making a dll.
          boolean isDllGenerated = linkerArgs.stream().anyMatch(arg -> arg.startsWith("/DLL"));
          if (isDllGenerated) {
            Path implibPath = importLibraryPath(output);
            builder.put("implib", implibPath);
          }
          return builder.build();
        }
      };

  public WindowsLinker(Tool tool) {
    super(tool);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(ImmutableMap<Path, Path> cellRootMap) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input, SourcePathResolver resolver) {
    if (input instanceof HasSourcePath) {
      SourcePath path = ((HasSourcePath) input).getPath();
      String fileName = resolver.getAbsolutePath(path).getFileName().toString();
      return ImmutableList.of(input, StringArg.of("/WHOLEARCHIVE:" + fileName));
    }
    throw new UnsupportedOperationException(
        "linkWhole requires an arg that implements HasSourcePath");
  }

  @Override
  public Iterable<String> soname(String arg) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> fileList(Path fileListPath) {
    return ImmutableList.of();
  }

  @Override
  public String origin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String libOrigin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String searchPathEnvVar() {
    return "PATH";
  }

  @Override
  public String preloadEnvVar() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      BuildTarget target,
      ImmutableList<? extends SourcePath> symbolFiles) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> getSharedLibFlag() {
    return ImmutableList.of(StringArg.of("/DLL"));
  }

  @Override
  public Iterable<String> outputArgs(String path) {
    return ImmutableList.of("/OUT:" + path);
  }

  @Override
  public SharedLibraryLoadingType getSharedLibraryLoadingType() {
    return SharedLibraryLoadingType.THE_SAME_DIRECTORY;
  }

  @Override
  public Optional<ExtraOutputsDeriver> getExtraOutputsDeriver() {
    return Optional.of(WINDOWS_EXTRA_OUTPUTS_DERIVER);
  }

  @Override
  public Iterable<Arg> importLibrary(Path output) {
    return StringArg.from("/IMPLIB:" + importLibraryPath(output));
  }

  @Override
  public Path importLibraryPath(Path output) {
    return Paths.get(output + ".imp.lib");
  }

  @Override
  public boolean getUseUnixPathSeparator() {
    return false;
  }
}
