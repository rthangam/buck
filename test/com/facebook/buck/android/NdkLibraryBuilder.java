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
package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.impl.StaticUnresolvedNdkCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;

public class NdkLibraryBuilder
    extends AbstractNodeBuilder<
        NdkLibraryDescriptionArg.Builder,
        NdkLibraryDescriptionArg,
        NdkLibraryDescription,
        NdkLibrary> {

  private static final UnresolvedNdkCxxPlatform DEFAULT_NDK_PLATFORM =
      StaticUnresolvedNdkCxxPlatform.of(
          NdkCxxPlatform.builder()
              .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
              .setCxxRuntime(NdkCxxRuntime.GNUSTL)
              .setCxxSharedRuntimePath(FakeSourcePath.of("runtime"))
              .setObjdump(new CommandTool.Builder().addArg("objdump").build())
              .build());

  public static final ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> NDK_PLATFORMS =
      ImmutableMap.<TargetCpuType, UnresolvedNdkCxxPlatform>builder()
          .put(TargetCpuType.ARM, DEFAULT_NDK_PLATFORM)
          .put(TargetCpuType.ARMV7, DEFAULT_NDK_PLATFORM)
          .put(TargetCpuType.X86, DEFAULT_NDK_PLATFORM)
          .build();

  public NdkLibraryBuilder(BuildTarget target) {
    this(target, new FakeProjectFilesystem());
  }

  public NdkLibraryBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    this(target, filesystem, createToolchainProviderForNdkLibrary());
  }

  public NdkLibraryBuilder(BuildTarget target, ToolchainProvider toolchainProvider) {
    this(target, new FakeProjectFilesystem(), toolchainProvider);
  }

  public NdkLibraryBuilder(
      BuildTarget target, ProjectFilesystem filesystem, ToolchainProvider toolchainProvider) {
    super(
        new NdkLibraryDescription(toolchainProvider) {
          @Override
          protected ImmutableSortedSet<SourcePath> findSources(
              ProjectFilesystem filesystem, Path buildRulePath) {
            return ImmutableSortedSet.of(
                PathSourcePath.of(filesystem, buildRulePath.resolve("Android.mk")));
          }
        },
        target,
        filesystem,
        toolchainProvider);
  }

  public static ToolchainProvider createToolchainProviderForNdkLibrary() {
    return new ToolchainProviderBuilder()
        .withToolchain(
            NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.of(NDK_PLATFORMS))
        .withToolchain(
            AndroidNdk.DEFAULT_NAME,
            AndroidNdk.of("12b", Paths.get("/android/ndk"), false, new ExecutableFinder()))
        .build();
  }

  public NdkLibraryBuilder addDep(BuildTarget target) {
    getArgForPopulating().addDeps(target);
    return this;
  }

  public NdkLibraryBuilder setFlags(Iterable<String> flags) {
    getArgForPopulating()
        .setFlags(
            Iterables.transform(
                flags, flag -> StringWithMacros.of(ImmutableList.of(Either.ofLeft(flag)))));
    return this;
  }

  public NdkLibraryBuilder setIsAsset(boolean isAsset) {
    getArgForPopulating().setIsAsset(isAsset);
    return this;
  }
}
