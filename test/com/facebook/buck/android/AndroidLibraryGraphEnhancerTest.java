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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavacFactoryHelper;
import com.facebook.buck.jvm.java.JavacLanguageLevelOptions;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacPluginParams;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.util.DependencyMode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidLibraryGraphEnhancerTest {

  @Test
  public void testEmptyResources() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            buildTarget,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(),
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            DependencyMode.FIRST_ORDER,
            /* forceFinalResourceIds */ false,
            /* unionPackage */ Optional.empty(),
            /* rName */ Optional.empty(),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);

    Optional<DummyRDotJava> result =
        graphEnhancer.getBuildableForAndroidResources(
            new TestActionGraphBuilder(), /* createdBuildableIfEmptyDeps */ false);
    assertFalse(result.isPresent());
  }

  @Test
  public void testBuildRuleResolverCaching() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            buildTarget,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(),
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            DependencyMode.FIRST_ORDER,
            /* forceFinalResourceIds */ false,
            /* unionPackage */ Optional.empty(),
            /* rName */ Optional.empty(),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Optional<DummyRDotJava> result =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createdBuildableIfEmptyDeps */ true);
    Optional<DummyRDotJava> secondResult =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createdBuildableIfEmptyDeps */ true);
    assertThat(result.get(), Matchers.sameInstance(secondResult.get()));
  }

  @Test
  public void testBuildableIsCreated() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule resourceRule1 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res1"))
                .build());
    BuildRule resourceRule2 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res2"))
                .build());

    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            buildTarget,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(resourceRule1, resourceRule2),
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            DependencyMode.FIRST_ORDER,
            /* forceFinalResourceIds */ false,
            /* unionPackage */ Optional.empty(),
            /* rName */ Optional.empty(),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);

    Optional<DummyRDotJava> dummyRDotJava =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createBuildableIfEmptyDeps */ false);

    assertTrue(dummyRDotJava.isPresent());
    assertEquals(
        "DummyRDotJava must contain these exact AndroidResourceRules.",
        // Note: these are the reverse order to which they are in the buildRuleParams.
        ImmutableList.of(resourceRule1, resourceRule2),
        dummyRDotJava.get().getAndroidResourceDeps());

    assertEquals(
        "//java/com/example:library#dummy_r_dot_java", dummyRDotJava.get().getFullyQualifiedName());
    assertEquals(
        "DummyRDotJava must depend on the two AndroidResourceRules.",
        ImmutableSet.of("//android_res/com/example:res1", "//android_res/com/example:res2"),
        dummyRDotJava.get().getBuildDeps().stream()
            .map(Object::toString)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testCreatedBuildableHasOverriddenJavacConfig() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/com/example:library");
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule resourceRule1 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res1"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res1"))
                .build());
    BuildRule resourceRule2 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//android_res/com/example:res2"))
                .setRDotJavaPackage("com.facebook")
                .setRes(FakeSourcePath.of("android_res/com/example/res2"))
                .build());

    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            buildTarget,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(resourceRule1, resourceRule2),
            DEFAULT_JAVAC,
            JavacOptions.builder(ANDROID_JAVAC_OPTIONS)
                .setJavaAnnotationProcessorParams(
                    JavacPluginParams.builder().setProcessOnly(true).build())
                .setLanguageLevelOptions(
                    JavacLanguageLevelOptions.builder()
                        .setSourceLevel("7")
                        .setTargetLevel("7")
                        .build())
                .build(),
            DependencyMode.FIRST_ORDER,
            /* forceFinalResourceIds */ false,
            /* unionPackage */ Optional.empty(),
            /* rName */ Optional.empty(),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);
    Optional<DummyRDotJava> dummyRDotJava =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createBuildableIfEmptyDeps */ false);

    assertTrue(dummyRDotJava.isPresent());
    JavacOptions javacOptions =
        ((JavacToJarStepFactory) dummyRDotJava.get().getCompileStepFactory()).getJavacOptions();
    assertFalse(javacOptions.getJavaAnnotationProcessorParams().getProcessOnly());
    assertEquals("7", javacOptions.getLanguageLevelOptions().getSourceLevel());
  }

  @Test
  public void testDummyRDotJavaRuleInheritsJavacOptionsDepsAndNoOthers() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule javacDep = new FakeJavaLibrary(BuildTargetFactory.newInstance("//:javac_dep"));
    graphBuilder.addToIndex(javacDep);
    FakeBuildRule dep = new FakeJavaLibrary(BuildTargetFactory.newInstance("//:dep"));
    graphBuilder.addToIndex(dep);
    JavaBuckConfig javaConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("tools", ImmutableMap.of("javac_jar", "//:javac_dep")))
            .build()
            .getView(JavaBuckConfig.class);
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    JavacOptions options =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder().setSourceLevel("5").setTargetLevel("5").build())
            .build();
    AndroidLibraryGraphEnhancer graphEnhancer =
        new AndroidLibraryGraphEnhancer(
            target,
            new FakeProjectFilesystem(),
            ImmutableSortedSet.of(dep),
            JavacFactoryHelper.createJavacFactory(javaConfig).create(graphBuilder, null),
            options,
            DependencyMode.FIRST_ORDER,
            /* forceFinalResourceIds */ false,
            /* unionPackage */ Optional.empty(),
            /* rName */ Optional.empty(),
            /* useOldStyleableFormat */ false,
            /* skipNonUnionRDotJava */ false);
    Optional<DummyRDotJava> result =
        graphEnhancer.getBuildableForAndroidResources(
            graphBuilder, /* createdBuildableIfEmptyDeps */ true);
    assertTrue(result.isPresent());
    assertThat(result.get().getBuildDeps(), Matchers.contains(javacDep));
  }
}
