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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class OmnibusTest {

  @Test
  public void includedDeps() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(),
            new TestActionGraphBuilder());
    assertThat(
        spec.getGraph().getNodes(),
        Matchers.containsInAnyOrder(a.getBuildTarget(), b.getBuildTarget()));
    assertThat(
        spec.getBody().keySet(),
        Matchers.containsInAnyOrder(a.getBuildTarget(), b.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.empty());
    assertThat(spec.getExcluded().keySet(), Matchers.empty());

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE),
        b.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void excludedAndIncludedDeps() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkable b = new OmnibusExcludedNode("//:b");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(),
            new TestActionGraphBuilder());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.containsInAnyOrder(b.getBuildTarget()));
    assertThat(spec.getExcluded().keySet(), Matchers.containsInAnyOrder(b.getBuildTarget()));

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(), b.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver),
        b.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void excludedDepExcludesTransitiveDep() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkable c = new OmnibusExcludedNode("//:c", ImmutableList.of(b));
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, c));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(),
            new TestActionGraphBuilder());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.containsInAnyOrder(c.getBuildTarget()));
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(b.getBuildTarget(), c.getBuildTarget()));

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            c.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver),
        c.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertThat(
        libs.get(c.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void depOfExcludedRoot() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a));
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkable excludedRoot = new OmnibusNode("//:excluded_root", ImmutableList.of(b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot),
            new TestActionGraphBuilder());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.empty());
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(excludedRoot.getBuildTarget(), b.getBuildTarget()));

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of(excludedRoot)));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            excludedRoot.getBuildTarget().toString(),
            b.getBuildTarget().toString(),
            "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver));
    assertThat(
        libs.get(excludedRoot.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertThat(
        libs.get(b.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get("libomnibus.so")),
        pathResolver,
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void commondDepOfIncludedAndExcludedRoots() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a));
    NativeLinkable excludedRoot = new OmnibusNode("//:excluded_root", ImmutableList.of(a));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(excludedRoot),
            new TestActionGraphBuilder());
    assertThat(spec.getGraph().getNodes(), Matchers.empty());
    assertThat(spec.getBody().keySet(), Matchers.empty());
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.containsInAnyOrder(a.getBuildTarget()));
    assertThat(
        spec.getExcluded().keySet(),
        Matchers.containsInAnyOrder(excludedRoot.getBuildTarget(), a.getBuildTarget()));

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of(excludedRoot)));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(
            root.getBuildTarget().toString(),
            excludedRoot.getBuildTarget().toString(),
            a.getBuildTarget().toString()));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver));
    assertThat(
        libs.get(excludedRoot.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
    assertThat(
        libs.get(a.getBuildTarget().toString()),
        Matchers.not(Matchers.instanceOf(ExplicitBuildTargetSourcePath.class)));
  }

  @Test
  public void unusedStaticDepsAreNotIncludedInBody() throws NoSuchBuildTargetException {
    NativeLinkable a =
        new OmnibusNode(
            "//:a", ImmutableList.of(), ImmutableList.of(), NativeLinkable.Linkage.STATIC);
    NativeLinkable b = new OmnibusNode("//:b");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a, b));

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(root),
            ImmutableList.of(),
            new TestActionGraphBuilder());
    assertThat(spec.getGraph().getNodes(), Matchers.containsInAnyOrder(b.getBuildTarget()));
    assertThat(spec.getBody().keySet(), Matchers.containsInAnyOrder(b.getBuildTarget()));
    assertThat(spec.getRoots().keySet(), Matchers.containsInAnyOrder(root.getBuildTarget()));
    assertThat(spec.getDeps().keySet(), Matchers.empty());
    assertThat(spec.getExcluded().keySet(), Matchers.empty());

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        libs.keySet(),
        Matchers.containsInAnyOrder(root.getBuildTarget().toString(), "libomnibus.so"));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())),
        pathResolver,
        root.getNativeLinkTargetInput(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder, pathResolver),
        a.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
    assertCxxLinkContainsNativeLinkableInput(
        getCxxLinkRule(graphBuilder, libs.get("libomnibus.so")),
        pathResolver,
        b.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void excludedStaticRootsProduceSharedLibraries() throws NoSuchBuildTargetException {
    NativeLinkTarget includedRoot = new OmnibusRootNode("//:included", ImmutableList.of());
    NativeLinkable excludedRoot =
        new OmnibusNode(
            "//:excluded", ImmutableList.of(), ImmutableList.of(), NativeLinkable.Linkage.STATIC);

    // Verify the spec.
    Omnibus.OmnibusSpec spec =
        Omnibus.buildSpec(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableList.of(includedRoot),
            ImmutableList.of(excludedRoot),
            new TestActionGraphBuilder());
    assertThat(spec.getExcludedRoots(), Matchers.containsInAnyOrder(excludedRoot.getBuildTarget()));
    assertThat(
        spec.getExcluded().keySet(), Matchers.containsInAnyOrder(excludedRoot.getBuildTarget()));

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                projectFilesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(projectFilesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(),
                ImmutableList.of(includedRoot),
                ImmutableList.of(excludedRoot)));
    assertThat(libs.keySet(), Matchers.hasItem(excludedRoot.getBuildTarget().toString()));
  }

  @Test
  public void extraLdFlags() throws NoSuchBuildTargetException {
    NativeLinkable a = new OmnibusNode("//:a");
    NativeLinkTarget root = new OmnibusRootNode("//:root", ImmutableList.of(a));
    String flag = "-flag";

    // Verify the libs.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ImmutableMap<String, SourcePath> libs =
        toSonameMap(
            Omnibus.getSharedLibraries(
                target,
                filesystem,
                TestBuildRuleParams.create(),
                TestCellPathResolver.get(filesystem),
                graphBuilder,
                CxxPlatformUtils.DEFAULT_CONFIG,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                ImmutableList.of(StringArg.of(flag)),
                ImmutableList.of(root),
                ImmutableList.of()));
    assertThat(
        Arg.stringify(
            getCxxLinkRule(graphBuilder, libs.get(root.getBuildTarget().toString())).getArgs(),
            pathResolver),
        Matchers.not(Matchers.hasItem(flag)));
    assertThat(
        Arg.stringify(
            getCxxLinkRule(graphBuilder, libs.get("libomnibus.so")).getArgs(), pathResolver),
        Matchers.hasItem(flag));
  }

  private CxxLink getCxxLinkRule(SourcePathRuleFinder ruleFinder, SourcePath path) {
    return ((CxxLink) ruleFinder.getRule((ExplicitBuildTargetSourcePath) path));
  }

  private void assertCxxLinkContainsNativeLinkableInput(
      CxxLink link, SourcePathResolver pathResolver, NativeLinkableInput... inputs) {
    for (NativeLinkableInput input : inputs) {
      assertThat(
          Arg.stringify(link.getArgs(), pathResolver),
          Matchers.hasItems(Arg.stringify(input.getArgs(), pathResolver).toArray(new String[1])));
    }
  }

  private ImmutableMap<String, SourcePath> toSonameMap(OmnibusLibraries libraries) {
    ImmutableMap.Builder<String, SourcePath> map = ImmutableMap.builder();
    for (Map.Entry<BuildTarget, OmnibusRoot> root : libraries.getRoots().entrySet()) {
      map.put(root.getKey().toString(), root.getValue().getPath());
    }
    for (OmnibusLibrary library : libraries.getLibraries()) {
      map.put(library.getSoname(), library.getPath());
    }
    return map.build();
  }
}
