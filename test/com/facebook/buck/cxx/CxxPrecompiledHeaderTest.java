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

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxPrecompiledHeaderTest {

  @Test
  public void generatesPchStepShouldUseCorrectLang() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Preprocessor preprocessorSupportingPch =
        new GccPreprocessor(
            CxxPlatformUtils.DEFAULT_PLATFORM
                .getCpp()
                .resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE)) {
          @Override
          public boolean supportsPrecompiledHeaders() {
            return true;
          }
        };
    Compiler compiler =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .getCxx()
            .resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE);
    CxxPrecompiledHeader precompiledHeader =
        new CxxPrecompiledHeader(
            /* canPrecompile */ true,
            target,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(),
            Paths.get("dir/foo.hash1.hash2.gch"),
            new PreprocessorDelegate(
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                FakeSourcePath.of("./"),
                preprocessorSupportingPch,
                PreprocessorFlags.builder().build(),
                CxxDescriptionEnhancer.frameworkPathToSearchPath(
                    CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder.getSourcePathResolver()),
                /* leadingIncludePaths */ Optional.empty(),
                Optional.of(new FakeBuildRule(target.withFlavors(InternalFlavor.of("deps")))),
                ImmutableSortedSet.of()),
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                compiler,
                CxxToolFlags.of(),
                Optional.empty()),
            CxxToolFlags.of(),
            FakeSourcePath.of("foo.h"),
            CxxSource.Type.C,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER);
    graphBuilder.addToIndex(precompiledHeader);
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver());
    ImmutableList<Step> postBuildSteps =
        precompiledHeader.getBuildSteps(buildContext, new FakeBuildableContext());
    CxxPreprocessAndCompileStep step =
        Iterables.getOnlyElement(
            Iterables.filter(postBuildSteps, CxxPreprocessAndCompileStep.class));
    assertThat(
        "step that generates pch should have correct flags",
        step.getCommand(),
        hasItem(CxxSource.Type.C.getPrecompiledHeaderLanguage().get()));
  }
}
