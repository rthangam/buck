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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class RobolectricTestRuleTest {

  private class ResourceRule implements HasAndroidResourceDeps {
    private final SourcePath resourceDirectory;
    private final SourcePath assetsDirectory;

    ResourceRule(SourcePath resourceDirectory) {
      this(resourceDirectory, null);
    }

    ResourceRule(SourcePath resourceDirectory, SourcePath assetsDirectory) {
      this.resourceDirectory = resourceDirectory;
      this.assetsDirectory = assetsDirectory;
    }

    @Override
    public SourcePath getPathToTextSymbolsFile() {
      return null;
    }

    @Override
    public SourcePath getPathToRDotJavaPackageFile() {
      return null;
    }

    @Override
    public String getRDotJavaPackage() {
      return null;
    }

    @Override
    public SourcePath getRes() {
      return resourceDirectory;
    }

    @Override
    public SourcePath getAssets() {
      return assetsDirectory;
    }

    @Override
    public BuildTarget getBuildTarget() {
      return null;
    }
  }

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testRobolectricContainsAllResourceDependenciesInResVmArg() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    ImmutableList.Builder<HasAndroidResourceDeps> resDepsBuilder = ImmutableList.builder();
    for (int i = 0; i < 10; i++) {
      String path = "java/src/com/facebook/base/" + i + "/res";
      filesystem.mkdirs(Paths.get(path).resolve("values"));
      resDepsBuilder.add(new ResourceRule(FakeSourcePath.of(filesystem, path)));
    }
    ImmutableList<HasAndroidResourceDeps> resDeps = resDepsBuilder.build();

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    String result =
        robolectricTest.getRobolectricResourceDirectoriesArg(
            graphBuilder.getSourcePathResolver(), resDeps);
    for (HasAndroidResourceDeps dep : resDeps) {
      // Every value should be a PathSourcePath
      assertTrue(
          result + " does not contain " + dep.getRes(),
          result.contains(((PathSourcePath) dep.getRes()).getRelativePath().toString()));
    }
  }

  @Test
  public void testRobolectricContainsAllResourceDependenciesInResVmArgAsFile() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    ImmutableList.Builder<HasAndroidResourceDeps> resDepsBuilder = ImmutableList.builder();
    for (int i = 0; i < 10; i++) {
      String path = "java/src/com/facebook/base/" + i + "/res";
      filesystem.mkdirs(Paths.get(path).resolve("values"));
      resDepsBuilder.add(new ResourceRule(FakeSourcePath.of(path)));
    }
    ImmutableList<HasAndroidResourceDeps> resDeps = resDepsBuilder.build();

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    JavaBuckConfig javaBuckConfig =
        JavaBuckConfig.of(
            FakeBuckConfig.builder()
                .setSections("[test]", "pass_robolectric_directories_in_file = true")
                .build());

    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem, javaBuckConfig)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    Path resDirectoriesPath =
        RobolectricTest.getResourceDirectoriesPath(filesystem, robolectricBuildTarget);
    String result =
        robolectricTest.getRobolectricResourceDirectoriesArg(
            graphBuilder.getSourcePathResolver(), resDeps);
    assertEquals(
        "-Dbuck.robolectric_res_directories=@" + filesystem.resolve(resDirectoriesPath), result);
  }

  @Test
  public void testRobolectricContainsAllResourceDependenciesInAssetVmArgAsFile() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    ImmutableList.Builder<HasAndroidResourceDeps> resDepsBuilder = ImmutableList.builder();
    for (int i = 0; i < 10; i++) {
      String path = "java/src/com/facebook/base/" + i + "/res";
      filesystem.mkdirs(Paths.get(path).resolve("values"));
      String assetPath = "java/src/com/facebook/base/" + i + "/assets";
      resDepsBuilder.add(new ResourceRule(FakeSourcePath.of(path), FakeSourcePath.of(assetPath)));
    }
    ImmutableList<HasAndroidResourceDeps> resDeps = resDepsBuilder.build();

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    JavaBuckConfig javaBuckConfig =
        JavaBuckConfig.of(
            FakeBuckConfig.builder()
                .setSections("[test]", "pass_robolectric_directories_in_file = true")
                .build());

    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem, javaBuckConfig)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    Path assetDirectoriesPath =
        RobolectricTest.getAssetDirectoriesPath(filesystem, robolectricBuildTarget);
    String result =
        robolectricTest.getRobolectricAssetsDirectories(
            graphBuilder.getSourcePathResolver(), resDeps);
    assertEquals(
        "-Dbuck.robolectric_assets_directories=@" + filesystem.resolve(assetDirectoriesPath),
        result);
  }

  @Test
  public void testRobolectricResourceDependenciesVmArgHasCorrectFormat() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());
    filesystem.mkdirs(Paths.get("res1/values"));
    filesystem.mkdirs(Paths.get("res2/values"));
    filesystem.mkdirs(Paths.get("res3/values"));
    filesystem.mkdirs(Paths.get("res4_to_ignore"));

    Path resDep1 = Paths.get("res1");
    Path resDep2 = Paths.get("res2");
    Path resDep3 = Paths.get("res3");
    Path resDep4 = Paths.get("res4_to_ignore");

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    String result =
        robolectricTest.getRobolectricResourceDirectoriesArg(
            graphBuilder.getSourcePathResolver(),
            ImmutableList.of(
                new ResourceRule(FakeSourcePath.of(filesystem, resDep1)),
                new ResourceRule(FakeSourcePath.of(filesystem, resDep2)),
                new ResourceRule(null),
                new ResourceRule(FakeSourcePath.of(filesystem, resDep3)),
                new ResourceRule(FakeSourcePath.of(filesystem, resDep4))));

    String expectedVmArgBuilder =
        "-D"
            + RobolectricTest.LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME
            + "="
            + resDep1
            + File.pathSeparator
            + resDep2
            + File.pathSeparator
            + resDep3;
    assertEquals(expectedVmArgBuilder, result);
  }

  @Test
  public void testRobolectricThrowsIfResourceDirNotThere() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");
    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    try {
      robolectricTest.getRobolectricResourceDirectoriesArg(
          graphBuilder.getSourcePathResolver(),
          ImmutableList.of(new ResourceRule(FakeSourcePath.of(filesystem, "not_there_res"))));
      fail("Expected FileNotFoundException");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("not_there_res"));
    }
  }

  @Test
  public void testRobolectricAssetsDependenciesVmArgHasCorrectFormat() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());
    filesystem.mkdirs(Paths.get("assets1/svg"));
    filesystem.mkdirs(Paths.get("assets2/xml"));
    filesystem.mkdirs(Paths.get("assets3_to_ignore"));

    Path assetsDep1 = Paths.get("assets1");
    Path assetsDep2 = Paths.get("assets2");
    Path assetsDep3 = Paths.get("assets3_to_ignore");

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    String result =
        robolectricTest.getRobolectricAssetsDirectories(
            graphBuilder.getSourcePathResolver(),
            ImmutableList.of(
                new ResourceRule(null, FakeSourcePath.of(filesystem, assetsDep1)),
                new ResourceRule(null, null),
                new ResourceRule(null, FakeSourcePath.of(filesystem, assetsDep2)),
                new ResourceRule(null, FakeSourcePath.of(filesystem, assetsDep3))));

    String expectedVmArgBuilder =
        "-D"
            + RobolectricTest.LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME
            + "="
            + assetsDep1
            + File.pathSeparator
            + assetsDep2;
    assertEquals(expectedVmArgBuilder, result);
  }

  @Test
  public void testRobolectricThrowsIfAssetsDirNotThere() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");
    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem).build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(robolectricTestNode);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    try {
      robolectricTest.getRobolectricResourceDirectoriesArg(
          graphBuilder.getSourcePathResolver(),
          ImmutableList.of(new ResourceRule(FakeSourcePath.of(filesystem, "not_there_assets"))));
      fail("Expected FileNotFoundException");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("not_there_assets"));
    }
  }

  @Test
  public void runtimeDepsIncludeTransitiveResourcesAndDummyR() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    BuildTarget resGenRuleTarget = BuildTargetFactory.newInstance("//:res-gen");
    TargetNode<?> resGenRuleNode =
        GenruleBuilder.newGenruleBuilder(resGenRuleTarget).setOut("res-out").build();

    BuildTarget assetsGenRuleTarget = BuildTargetFactory.newInstance("//:assets-gen");
    TargetNode<?> assetsGenRuleNode =
        GenruleBuilder.newGenruleBuilder(assetsGenRuleTarget).setOut("assets-out").build();

    BuildTarget res2RuleTarget = BuildTargetFactory.newInstance("//:res2");
    TargetNode<?> res2Node =
        AndroidResourceBuilder.createBuilder(res2RuleTarget)
            .setRes(DefaultBuildTargetSourcePath.of(resGenRuleTarget))
            .setAssets(DefaultBuildTargetSourcePath.of(assetsGenRuleTarget))
            .setRDotJavaPackage("foo.bar")
            .build();

    BuildTarget robolectricBuildTarget =
        BuildTargetFactory.newInstance(
            "//java/src/com/facebook/base/robolectricTest:robolectricTest");
    TargetNode<?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(robolectricBuildTarget, filesystem)
            .addDep(res2RuleTarget)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            resGenRuleNode, assetsGenRuleNode, res2Node, robolectricTestNode);

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            targetGraph, RobolectricTestBuilder.createToolchainProviderForRobolectricTest());

    RobolectricTest robolectricTest =
        (RobolectricTest) graphBuilder.requireRule(robolectricBuildTarget);

    BuildRule resGenRule = graphBuilder.requireRule(resGenRuleTarget);
    BuildRule assetsGenRule = graphBuilder.requireRule(assetsGenRuleTarget);

    assertThat(
        robolectricTest.getRuntimeDeps(graphBuilder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItems(resGenRule.getBuildTarget(), assetsGenRule.getBuildTarget()));
  }
}
