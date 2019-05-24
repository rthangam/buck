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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltCxxLibraryGroupDescriptionTest {

  @Test
  public void exportedPreprocessorFlags() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    CxxPreprocessorDep lib =
        (CxxPreprocessorDep)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setExportedPreprocessorFlags(ImmutableList.of("-flag"))
                .build(graphBuilder);
    assertThat(
        lib.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getPreprocessorFlags(),
        Matchers.equalTo(CxxFlags.toLanguageFlags(StringArg.from("-flag"))));
  }

  @Test
  public void includeDirs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath includes = FakeSourcePath.of("include");
    CxxPreprocessorDep lib =
        (CxxPreprocessorDep)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setIncludeDirs(ImmutableList.of(includes))
                .build(graphBuilder);
    assertThat(
        lib.getCxxPreprocessorInput(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder).getIncludes(),
        Matchers.equalTo(
            ImmutableList.<CxxHeaders>of(
                CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includes))));
  }

  @Test
  public void staticLink() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath path = FakeSourcePath.of("include");
    NativeLinkable lib =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setStaticLink(ImmutableList.of("--something", "$(lib 0)", "--something-else"))
                .setStaticLibs(ImmutableList.of(path))
                .build(graphBuilder);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(
                    StringArg.of("--something"),
                    SourcePathArg.of(path),
                    StringArg.of("--something-else"))
                .build()));
  }

  @Test
  public void staticPicLink() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath path = FakeSourcePath.of("include");
    NativeLinkable lib =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setStaticPicLink(ImmutableList.of("--something", "$(lib 0)", "--something-else"))
                .setStaticPicLibs(ImmutableList.of(path))
                .build(graphBuilder);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(
                    StringArg.of("--something"),
                    SourcePathArg.of(path),
                    StringArg.of("--something-else"))
                .build()));
  }

  @Test
  public void sharedLink() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath lib1 = FakeSourcePath.of("dir/lib1.so");
    PathSourcePath lib2 = FakeSourcePath.of("dir/lib2.so");
    NativeLinkable lib =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setSharedLink(
                    ImmutableList.of(
                        "--something", "$(lib lib1.so)", "--something-else", "$(rel-lib lib2.so)"))
                .setSharedLibs(ImmutableMap.of("lib1.so", lib1, "lib2.so", lib2))
                .build(graphBuilder);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(
                    StringArg.of("--something"),
                    SourcePathArg.of(lib1),
                    StringArg.of("--something-else"),
                    new RelativeLinkArg(lib2))
                .build()));
  }

  @Test
  public void exportedDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    CxxLibrary dep =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build(graphBuilder);
    NativeLinkable lib =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
                .build(graphBuilder);
    assertThat(
        lib.getNativeLinkableExportedDepsForPlatform(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.contains(dep));
  }

  @Test
  public void providedSharedLibs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath lib1 = FakeSourcePath.of("dir/lib1.so");
    SourcePath lib2 = FakeSourcePath.of("dir/lib2.so");
    NativeLinkable lib =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(target)
                .setSharedLink(ImmutableList.of("$(lib lib1.so)", "$(lib lib2.so)"))
                .setSharedLibs(ImmutableMap.of("lib1.so", lib1))
                .setProvidedSharedLibs(ImmutableMap.of("lib2.so", lib2))
                .build(graphBuilder);
    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE),
        Matchers.equalTo(
            NativeLinkableInput.builder()
                .addArgs(SourcePathArg.of(lib1), SourcePathArg.of(lib2))
                .build()));
    assertThat(
        lib.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.equalTo(ImmutableMap.of("lib1.so", lib1)));
  }

  @Test
  public void preferredLinkage() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    NativeLinkable any =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(BuildTargetFactory.newInstance("//:any"))
                .setSharedLink(ImmutableList.of("-something"))
                .setStaticLink(ImmutableList.of("-something"))
                .build(graphBuilder);
    assertThat(
        any.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.ANY));

    NativeLinkable staticOnly =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(BuildTargetFactory.newInstance("//:static-only"))
                .setStaticLink(ImmutableList.of("-something"))
                .build(graphBuilder);
    assertThat(
        staticOnly.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.STATIC));

    NativeLinkable sharedOnly =
        (NativeLinkable)
            new PrebuiltCxxLibraryGroupBuilder(BuildTargetFactory.newInstance("//:shared-only"))
                .setSharedLink(ImmutableList.of("-something"))
                .build(graphBuilder);
    assertThat(
        sharedOnly.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.equalTo(NativeLinkable.Linkage.SHARED));
  }

  @Test
  public void cxxGenruleLib() {
    CxxGenruleBuilder cxxGenruleBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:dep")).setOut("libtest.so");
    PrebuiltCxxLibraryGroupBuilder builder =
        new PrebuiltCxxLibraryGroupBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setStaticLink(ImmutableList.of("$(lib 0)"))
            .setStaticLibs(
                ImmutableList.of(DefaultBuildTargetSourcePath.of(cxxGenruleBuilder.getTarget())));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(cxxGenruleBuilder.build(), builder.build()));

    CxxGenrule cxxGenrule = (CxxGenrule) cxxGenruleBuilder.build(graphBuilder);
    NativeLinkable library = (NativeLinkable) builder.build(graphBuilder);
    NativeLinkableInput input =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE);
    SourcePath lib = cxxGenrule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder);
    assertThat(input.getArgs(), Matchers.contains(SourcePathArg.of(lib)));
  }

  @Test
  public void supportedPlatforms() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    CxxLibrary dep1 =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep")).build(graphBuilder);
    CxxLibrary dep2 =
        (CxxLibrary)
            new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep2")).build(graphBuilder);
    BuildTarget target = BuildTargetFactory.newInstance("//:lib");
    SourcePath lib1 = FakeSourcePath.of("dir/lib1.so");
    SourcePath lib2 = FakeSourcePath.of("dir/lib2.so");
    BuildRule buildRule =
        new PrebuiltCxxLibraryGroupBuilder(target)
            .setSharedLink(ImmutableList.of("$(lib lib1.so)", "$(lib lib2.so)"))
            .setSharedLibs(ImmutableMap.of("lib1.so", lib1))
            .setProvidedSharedLibs(ImmutableMap.of("lib2.so", lib2))
            .setExportedDeps(ImmutableSortedSet.of(dep1.getBuildTarget()))
            .setDeps(ImmutableSortedSet.of(dep2.getBuildTarget()))
            .setSupportedPlatformsRegex(Pattern.compile("nothing"))
            .build(graphBuilder);

    NativeLinkable lib = (NativeLinkable) buildRule;

    assertThat(
        lib.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE),
        Matchers.equalTo(NativeLinkableInput.of()));

    assertThat(
        lib.getNativeLinkableExportedDepsForPlatform(
            CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.emptyIterable());

    assertThat(
        lib.getNativeLinkableDepsForPlatform(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.emptyIterable());

    assertThat(
        lib.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.anEmptyMap());

    CxxPreprocessorDep cxxPreprocessorDep = (CxxPreprocessorDep) buildRule;

    assertThat(
        cxxPreprocessorDep.getCxxPreprocessorDeps(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder),
        Matchers.emptyIterable());
  }
}
