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

package com.facebook.buck.swift;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class SwiftNativeLinkableTest {

  private Tool swiftcTool;
  private Tool swiftStdTool;
  private SourcePathResolver sourcePathResolver;

  @Before
  public void setUp() {
    swiftcTool = VersionedTool.of(FakeSourcePath.of("swiftc"), "foo", "1.0");
    swiftStdTool = VersionedTool.of(FakeSourcePath.of("swift-std"), "foo", "1.0");

    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    sourcePathResolver = buildRuleResolver.getSourcePathResolver();
  }

  @Test
  public void testStaticLinkerFlagsOnMobile() {
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            "iphoneos", ImmutableSet.of(), swiftcTool, Optional.of(swiftStdTool));

    ImmutableList.Builder<Arg> staticArgsBuilder = ImmutableList.builder();
    SwiftRuntimeNativeLinkable.populateLinkerArguments(
        staticArgsBuilder, swiftPlatform, Linker.LinkableDepType.STATIC);

    ImmutableList.Builder<Arg> sharedArgsBuilder = ImmutableList.builder();
    SwiftRuntimeNativeLinkable.populateLinkerArguments(
        sharedArgsBuilder, swiftPlatform, Linker.LinkableDepType.SHARED);

    ImmutableList<Arg> staticArgs = staticArgsBuilder.build();
    ImmutableList<Arg> sharedArgs = sharedArgsBuilder.build();

    // On iOS, Swift runtime is not available as static libs
    assertEquals(staticArgs, sharedArgs);
    assertEquals(
        Arg.stringify(sharedArgs, sourcePathResolver),
        ImmutableList.of(
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("/usr/lib/swift"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@executable_path/Frameworks"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@loader_path/Frameworks")));
  }

  @Test
  public void testStaticLinkerFlagsOnMac() {
    SwiftPlatform swiftPlatform =
        SwiftPlatformFactory.build(
            "macosx", ImmutableSet.of(), swiftcTool, Optional.of(swiftStdTool));

    ImmutableList.Builder<Arg> sharedArgsBuilder = ImmutableList.builder();
    SwiftRuntimeNativeLinkable.populateLinkerArguments(
        sharedArgsBuilder, swiftPlatform, Linker.LinkableDepType.SHARED);

    ImmutableList<Arg> sharedArgs = sharedArgsBuilder.build();
    assertEquals(
        Arg.stringify(sharedArgs, sourcePathResolver),
        ImmutableList.of(
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("/usr/lib/swift"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@executable_path/../Frameworks"),
            "-Xlinker",
            "-rpath",
            "-Xlinker",
            MorePaths.pathWithPlatformSeparators("@loader_path/../Frameworks")));
  }
}
