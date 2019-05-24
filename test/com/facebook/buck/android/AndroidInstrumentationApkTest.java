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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_CONFIG;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class AndroidInstrumentationApkTest {

  @Test
  public void testAndroidInstrumentationApkExcludesClassesFromInstrumentedApk() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget javaLibrary1Target = BuildTargetFactory.newInstance("//java/com/example:lib1");
    FakeJavaLibrary javaLibrary1 = new FakeJavaLibrary(javaLibrary1Target);

    FakeJavaLibrary javaLibrary2 =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance("//java/com/example:lib2"),
            /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary1)) {

          @Override
          public ImmutableSet<SourcePath> getTransitiveClasspaths() {
            return ImmutableSet.of(
                DefaultBuildTargetSourcePath.of(javaLibrary1Target), getSourcePathToOutput());
          }
        };

    BuildTarget javaLibrary3Target = BuildTargetFactory.newInstance("//java/com/example:lib3");
    FakeJavaLibrary javaLibrary3 = new FakeJavaLibrary(javaLibrary3Target);

    FakeJavaLibrary javaLibrary4 =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance("//java/com/example:lib4"),
            /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary3)) {
          @Override
          public ImmutableSet<SourcePath> getTransitiveClasspaths() {
            return ImmutableSet.of(
                DefaultBuildTargetSourcePath.of(javaLibrary3Target), getSourcePathToOutput());
          }
        };

    graphBuilder.addToIndex(javaLibrary1);
    graphBuilder.addToIndex(javaLibrary2);
    graphBuilder.addToIndex(javaLibrary3);
    graphBuilder.addToIndex(javaLibrary4);

    BuildRule keystore =
        KeystoreBuilder.createBuilder(BuildTargetFactory.newInstance("//keystores:debug"))
            .setProperties(FakeSourcePath.of("keystores/debug.properties"))
            .setStore(FakeSourcePath.of("keystores/debug.keystore"))
            .build(graphBuilder);

    // AndroidBinaryRule transitively depends on :lib1, :lib2, and :lib3.
    AndroidBinaryBuilder androidBinaryBuilder =
        AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//apps:app"));
    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(javaLibrary2.getBuildTarget(), javaLibrary3.getBuildTarget());
    androidBinaryBuilder
        .setManifest(FakeSourcePath.of("apps/AndroidManifest.xml"))
        .setKeystore(keystore.getBuildTarget())
        .setOriginalDeps(originalDepsTargets);
    AndroidBinary androidBinary = androidBinaryBuilder.build(graphBuilder);

    // AndroidInstrumentationApk transitively depends on :lib1, :lib2, :lib3, and :lib4.
    ImmutableSortedSet<BuildTarget> apkOriginalDepsTargets =
        ImmutableSortedSet.of(javaLibrary2.getBuildTarget(), javaLibrary4.getBuildTarget());
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//apps:instrumentation");
    AndroidInstrumentationApkDescriptionArg arg =
        AndroidInstrumentationApkDescriptionArg.builder()
            .setName(buildTarget.getShortName())
            .setApk(androidBinary.getBuildTarget())
            .setDeps(apkOriginalDepsTargets)
            .setManifest(FakeSourcePath.of("apps/InstrumentationAndroidManifest.xml"))
            .build();

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params =
        TestBuildRuleParams.create()
            .withDeclaredDeps(graphBuilder.getAllRules(apkOriginalDepsTargets))
            .withExtraDeps(ImmutableSortedSet.of(androidBinary));
    ToolchainProvider toolchainProvider =
        AndroidInstrumentationApkBuilder.createToolchainProviderForAndroidInstrumentationApk();
    AndroidInstrumentationApk androidInstrumentationApk =
        (AndroidInstrumentationApk)
            new AndroidInstrumentationApkDescription(
                    DEFAULT_JAVA_CONFIG,
                    new ProGuardConfig(FakeBuckConfig.builder().build()),
                    CxxPlatformUtils.DEFAULT_CONFIG,
                    new DxConfig(FakeBuckConfig.builder().build()),
                    toolchainProvider)
                .createBuildRule(
                    TestBuildRuleCreationContextFactory.create(
                        graphBuilder, projectFilesystem, toolchainProvider),
                    buildTarget,
                    params,
                    arg);

    assertEquals(
        "//apps:app should have three JAR files to dex.",
        ImmutableSet.of(
            BuildTargetPaths.getGenPath(
                javaLibrary1.getProjectFilesystem(), javaLibrary1.getBuildTarget(), "%s.jar"),
            BuildTargetPaths.getGenPath(
                javaLibrary2.getProjectFilesystem(), javaLibrary2.getBuildTarget(), "%s.jar"),
            BuildTargetPaths.getGenPath(
                javaLibrary3.getProjectFilesystem(), javaLibrary3.getBuildTarget(), "%s.jar")),
        androidBinary.getAndroidPackageableCollection().getClasspathEntriesToDex().stream()
            .map(graphBuilder.getSourcePathResolver()::getRelativePath)
            .collect(ImmutableSet.toImmutableSet()));
    assertEquals(
        "//apps:instrumentation should have one JAR file to dex.",
        ImmutableSet.of(
            BuildTargetPaths.getGenPath(
                javaLibrary4.getProjectFilesystem(), javaLibrary4.getBuildTarget(), "%s.jar")),
        androidInstrumentationApk.getAndroidPackageableCollection().getClasspathEntriesToDex()
            .stream()
            .map(graphBuilder.getSourcePathResolver()::getRelativePath)
            .collect(ImmutableSet.toImmutableSet()));
  }
}
