/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleLibraryDescriptionTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    assumeThat(Platform.detect(), is(Platform.MACOS));
  }

  @Test
  public void linkerFlagsLocationMacro() {
    BuildTarget sandboxTarget =
        BuildTargetFactory.newInstance("//:rule").withFlavors(DefaultCxxPlatforms.FLAVOR);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(new AppleLibraryBuilder(sandboxTarget).build()));
    BuildTarget target =
        BuildTargetFactory.newInstance("//:rule")
            .withFlavors(DefaultCxxPlatforms.FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR);
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    AppleLibraryBuilder builder =
        new AppleLibraryBuilder(target)
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format(
                        "--linker-script=%s", LocationMacro.of(dep.getBuildTarget()))))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.c"))));
    assertThat(builder.build().getExtraDeps(), Matchers.hasItem(dep.getBuildTarget()));
    BuildRule binary = builder.build(graphBuilder);
    assertThat(binary, Matchers.instanceOf(CxxLink.class));
    assertThat(
        Arg.stringify(((CxxLink) binary).getArgs(), graphBuilder.getSourcePathResolver()),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(binary.getBuildDeps(), Matchers.hasItem(dep));
  }

  @Test
  public void swiftMetadata() {
    SourcePath objCSourcePath = FakeSourcePath.of("foo.m");
    SourcePath swiftSourcePath = FakeSourcePath.of("bar.swift");

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:library");
    TargetNode<?> binaryNode =
        new AppleLibraryBuilder(binaryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(objCSourcePath), SourceWithFlags.of(swiftSourcePath)))
            .build();

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(binaryNode));

    BuildTarget swiftMetadataTarget =
        binaryTarget.withFlavors(
            AppleLibraryDescription.MetadataType.APPLE_SWIFT_METADATA.getFlavor());
    Optional<AppleLibrarySwiftMetadata> metadata =
        graphBuilder.requireMetadata(swiftMetadataTarget, AppleLibrarySwiftMetadata.class);
    assertTrue(metadata.isPresent());

    assertEquals(metadata.get().getNonSwiftSources().size(), 1);
    SourcePath expectedObjCSourcePath =
        metadata.get().getNonSwiftSources().iterator().next().getSourcePath();
    assertSame(objCSourcePath, expectedObjCSourcePath);

    assertEquals(metadata.get().getSwiftSources().size(), 1);
    SourcePath expectedSwiftSourcePath =
        metadata.get().getSwiftSources().iterator().next().getSourcePath();
    assertSame(swiftSourcePath, expectedSwiftSourcePath);
  }

  @Test
  public void modularObjcFlags() {
    BuildTarget libTarget = BuildTargetFactory.newInstance("//:library");
    TargetNode<AppleLibraryDescriptionArg> libNode =
        new AppleLibraryBuilder(libTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.m"))))
            .setCompilerFlags(ImmutableList.of("-DDEBUG=1"))
            .setModular(true)
            .build();

    BuildRuleResolver buildRuleResolver =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(libNode));

    CxxLibraryDescriptionArg.Builder delegateArgBuilder =
        CxxLibraryDescriptionArg.builder().from(libNode.getConstructorArg());

    AppleDescriptions.populateCxxLibraryDescriptionArg(
        buildRuleResolver.getSourcePathResolver(),
        delegateArgBuilder,
        libNode.getConstructorArg(),
        libTarget);
    CxxLibraryDescriptionArg delegateArg = delegateArgBuilder.build();
    assertThat(
        delegateArg.getCompilerFlags(),
        containsInAnyOrder(
            StringWithMacrosUtils.format("-fmodule-name=library"),
            StringWithMacrosUtils.format("-DDEBUG=1")));
  }

  @Test
  public void noModularBridgingHeader() {
    thrown.expectMessage("Cannot be modular=True and have a bridging_header in the same rule");
    AppleLibraryDescriptionArg.builder()
        .setName("fake")
        .setModular(true)
        .setBridgingHeader(FakeSourcePath.of("header.h"))
        .build();
  }
}
