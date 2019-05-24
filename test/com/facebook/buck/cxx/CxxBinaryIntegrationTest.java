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

package com.facebook.buck.cxx;

import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static java.io.File.pathSeparator;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.InferHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CxxBinaryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
  }

  @Test
  public void testInferCxxBinaryDepsCaching() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor())
            .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that if the file in the binary target changes, then all the deps will be fetched
     * from the cache
     */
    String sourceName = "src_with_deps.c";
    workspace.replaceFileContents("foo/" + sourceName, "10", "30");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget captureBuildTarget = cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget inferAnalysisTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    // this is the flavored version of the top level target (the one give in input to buck)
    BuildTarget inferReportTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    String bt;
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      bt = buildTarget.toString();
      if (buildTarget
              .getFlavors()
              .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
          || buildTarget.getFlavors().contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)
          || bt.equals(inferAnalysisTarget.toString())
          || bt.equals(captureBuildTarget.toString())
          || bt.equals(inferReportTarget.toString())
          || bt.equals(aggregatedDepsTarget.toString())) {
        buildLog.assertTargetBuiltLocally(bt);
      } else {
        buildLog.assertTargetWasFetchedFromCache(buildTarget);
      }
    }
  }

  @Test
  public void testInferCxxBinaryDepsInvalidateCacheWhenVersionChanges() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_deps");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor())
            .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that if the version of infer changes, then all the infer-related targets are
     * recomputed
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("fake-infer/fake-bin/infer", "0.12345", "9.9999");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    String sourceName = "src_with_deps.c";
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget topCaptureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    BuildTarget topInferAnalysisTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    BuildTarget topInferReportTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    BuildTarget depOneBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_one");
    String depOneSourceName = "dep_one.c";
    CxxSourceRuleFactory depOneSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depOneBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depOneCaptureBuildTarget =
        depOneSourceRuleFactory.createInferCaptureBuildTarget(depOneSourceName);

    BuildTarget depOneInferAnalysisTarget =
        depOneCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(), CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    BuildTarget depTwoBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_two");
    CxxSourceRuleFactory depTwoSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depTwoBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depTwoCaptureBuildTarget =
        depTwoSourceRuleFactory.createInferCaptureBuildTarget("dep_two.c");

    BuildTarget depTwoInferAnalysisTarget =
        depTwoCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(), CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    ImmutableSet<String> locallyBuiltTargets =
        ImmutableSet.of(
            cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget().toString(),
            topCaptureBuildTarget.toString(),
            topInferAnalysisTarget.toString(),
            topInferReportTarget.toString(),
            depOneSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget().toString(),
            depOneCaptureBuildTarget.toString(),
            depOneInferAnalysisTarget.toString(),
            depTwoSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget().toString(),
            depTwoCaptureBuildTarget.toString(),
            depTwoInferAnalysisTarget.toString());

    // check that infer-related targets are getting rebuilt
    for (String t : locallyBuiltTargets) {
      buildLog.assertTargetBuiltLocally(t);
    }

    Set<String> builtFromCacheTargets =
        FluentIterable.from(buildLog.getAllTargets())
            // Filter out header symlink tree rules, as they are always built locally.
            .filter(
                target ->
                    (!target
                            .getFlavors()
                            .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)
                        && !target
                            .getFlavors()
                            .contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)))
            .transform(Object::toString)
            // Filter out any rules that are explicitly built locally.
            .filter(Predicates.not(locallyBuiltTargets::contains))
            .toSet();

    // check that all the other targets are fetched from the cache
    for (String t : builtFromCacheTargets) {
      buildLog.assertTargetWasFetchedFromCache(t);
    }
  }

  @Test
  public void testInferCxxBinaryWithoutDeps() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:simple");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor())
            .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that all the required build targets have been generated.
     */
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;

    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);
    // this is unflavored, but bounded to the InferCapture build rule
    BuildTarget captureBuildTarget = cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);
    // this is unflavored, but necessary to run the compiler successfully
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            inputBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget inferAnalysisTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    // this is flavored and corresponds to the top level target (the one give in input to buck)
    BuildTarget inferReportTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    ImmutableSortedSet.Builder<BuildTarget> targetsBuilder =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(
                aggregatedDepsTarget,
                headerSymlinkTreeTarget,
                captureBuildTarget,
                inferAnalysisTarget,
                inferReportTarget);

    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(buildLog.getAllTargets(), containsInAnyOrder(targetsBuilder.build().toArray()));
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(captureBuildTarget);
    buildLog.assertTargetBuiltLocally(inferAnalysisTarget);
    buildLog.assertTargetBuiltLocally(inferReportTarget);

    /*
     * Check that running a build again results in no builds since nothing has changed.
     */
    workspace.resetBuildLogFile(); // clear for new build
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(inferReportTarget), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(inferReportTarget);

    /*
     * Check that changing the source file results in running the capture/analysis rules again.
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents(sourceFull, "*s = 42;", "");
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    targetsBuilder =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(
                aggregatedDepsTarget,
                captureBuildTarget,
                inferAnalysisTarget,
                inferReportTarget,
                headerSymlinkTreeTarget);
    assertEquals(buildLog.getAllTargets(), targetsBuilder.build());
    buildLog.assertTargetBuiltLocally(captureBuildTarget);
    buildLog.assertTargetBuiltLocally(inferAnalysisTarget);
    buildLog.assertTargetHadMatchingRuleKey(aggregatedDepsTarget);
  }

  @Test
  public void testInferCxxBinaryWithDeps() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:binary_with_deps");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor())
            .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that all the required build targets have been generated.
     */
    String sourceName = "src_with_deps.c";
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);
    // 1. create the targets of binary_with_deps
    // this is unflavored, but bounded to the InferCapture build rule
    BuildTarget topCaptureBuildTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget(sourceName);

    // this is unflavored, but necessary to run the compiler successfully
    BuildTarget topHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            inputBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());

    // this is flavored, and denotes the analysis step (generates a local report)
    BuildTarget topInferAnalysisTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    // this is flavored and corresponds to the top level target (the one give in input to buck)
    BuildTarget topInferReportTarget =
        inputBuildTarget.withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    BuildTarget topAggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // 2. create the targets of dep_one
    BuildTarget depOneBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_one");
    String depOneSourceName = "dep_one.c";
    String depOneSourceFull = "foo/" + depOneSourceName;
    CxxSourceRuleFactory depOneSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depOneBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depOneCaptureBuildTarget =
        depOneSourceRuleFactory.createInferCaptureBuildTarget(depOneSourceName);

    BuildTarget depOneHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depOneBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());

    BuildTarget depOneExportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depOneBuildTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    BuildTarget depOneInferAnalysisTarget =
        depOneCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(), CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    BuildTarget depOneAggregatedDepsTarget =
        depOneSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // 3. create the targets of dep_two
    BuildTarget depTwoBuildTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:dep_two");
    CxxSourceRuleFactory depTwoSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depTwoBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget depTwoCaptureBuildTarget =
        depTwoSourceRuleFactory.createInferCaptureBuildTarget("dep_two.c");

    BuildTarget depTwoHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTwoBuildTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());

    BuildTarget depTwoExportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTwoBuildTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    BuildTarget depTwoInferAnalysisTarget =
        depTwoCaptureBuildTarget.withFlavors(
            cxxPlatform.getFlavor(), CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor());

    BuildTarget depTwoAggregatedDepsTarget =
        depTwoSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    ImmutableSet.Builder<BuildTarget> buildTargets =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(
                topAggregatedDepsTarget,
                topCaptureBuildTarget,
                topHeaderSymlinkTreeTarget,
                topInferAnalysisTarget,
                topInferReportTarget,
                depOneAggregatedDepsTarget,
                depOneCaptureBuildTarget,
                depOneHeaderSymlinkTreeTarget,
                depOneExportedHeaderSymlinkTreeTarget,
                depOneInferAnalysisTarget,
                depTwoAggregatedDepsTarget,
                depTwoCaptureBuildTarget,
                depTwoHeaderSymlinkTreeTarget,
                depTwoExportedHeaderSymlinkTreeTarget,
                depTwoInferAnalysisTarget);
    // Check all the targets are in the buildLog
    assertEquals(
        buildTargets.build(), ImmutableSet.copyOf(workspace.getBuildLog().getAllTargets()));

    /*
     * Check that running a build again results in no builds since nothing has changed.
     */
    workspace.resetBuildLogFile(); // clear for new build
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(topInferReportTarget), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(topInferReportTarget);

    /*
     * Check that if a library source file changes then the capture/analysis rules run again on
     * the main target and on dep_one only.
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents(depOneSourceFull, "flag > 0", "flag < 0");
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildTargets =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(
                topInferAnalysisTarget, // analysis runs again
                topInferReportTarget, // report runs again
                topCaptureBuildTarget, // cached
                depTwoInferAnalysisTarget, // cached
                depOneAggregatedDepsTarget,
                depOneHeaderSymlinkTreeTarget,
                depOneExportedHeaderSymlinkTreeTarget,
                depOneCaptureBuildTarget, // capture of the changed file runs again
                depOneInferAnalysisTarget // analysis of the library runs again
                );
    assertEquals(buildTargets.build(), buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(topInferAnalysisTarget);
    buildLog.assertTargetBuiltLocally(topInferReportTarget);
    buildLog.assertTargetHadMatchingRuleKey(topCaptureBuildTarget);
    buildLog.assertTargetHadMatchingRuleKey(depTwoInferAnalysisTarget);
    buildLog.assertTargetBuiltLocally(depOneCaptureBuildTarget);
    buildLog.assertTargetBuiltLocally(depOneInferAnalysisTarget);
    buildLog.assertTargetHadMatchingRuleKey(depOneAggregatedDepsTarget);
  }

  @Test
  public void testInferCxxBinaryWithDepsEmitsAllTheDependenciesResultsDirs() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt"))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt")));

    String sanitizedChainDepOne = sanitize("chain_dep_one.c.o");
    String sanitizedTopChain = sanitize("top_chain.c.o");
    String sanitizedChainDepTwo = sanitize("chain_dep_two.c.o");

    BuildTarget analyzeTopChainTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps#infer-analyze");
    BuildTarget captureTopChainTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_chain_deps#default,infer-capture-" + sanitizedTopChain);
    BuildTarget analyzeChainDepOneTarget =
        BuildTargetFactory.newInstance("//foo:chain_dep_one#default,infer-analyze");
    BuildTarget captureChainDepOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:chain_dep_one#default,infer-capture-" + sanitizedChainDepOne);
    BuildTarget analyzeChainDepTwoTarget =
        BuildTargetFactory.newInstance("//foo:chain_dep_two#default,infer-analyze");
    BuildTarget captureChainDepTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:chain_dep_two#default,infer-capture-" + sanitizedChainDepTwo);

    Path basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            analyzeTopChainTarget.getFullyQualifiedName()
                + "\t"
                + "[infer-analyze]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem, analyzeTopChainTarget, "infer-analysis-%s")),
            captureTopChainTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedTopChain
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(filesystem, captureTopChainTarget, "infer-out-%s")),
            analyzeChainDepOneTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-analyze]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem, analyzeChainDepOneTarget, "infer-analysis-%s")),
            captureChainDepOneTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedChainDepOne
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem, captureChainDepOneTarget, "infer-out-%s")),
            analyzeChainDepTwoTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-analyze]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem, analyzeChainDepTwoTarget, "infer-analysis-%s")),
            captureChainDepTwoTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedChainDepTwo
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(
                        filesystem, captureChainDepTwoTarget, "infer-out-%s")));

    assertEquals(expectedOutput, loggedDeps);
  }

  private static void registerCell(
      ProjectWorkspace cellToModifyConfigOf,
      String cellName,
      ProjectWorkspace cellToRegisterAsCellName)
      throws IOException {
    TestDataHelper.overrideBuckconfig(
        cellToModifyConfigOf,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of(
                cellName, cellToRegisterAsCellName.getPath(".").normalize().toString())));
  }

  @Test
  public void inferShouldBeAbleToUseMultipleXCell() throws IOException {

    Path rootWorkspacePath = tmp.getRoot();
    // create infertest workspace
    InferHelper.setupWorkspace(this, rootWorkspacePath, "infertest");

    // create infertest/inter-cell/multi-cell/primary sub-workspace as infer-configured one
    Path primaryRootPath = tmp.newFolder().toRealPath().normalize();
    ProjectWorkspace primary =
        InferHelper.setupCxxInferWorkspace(
            this,
            primaryRootPath,
            Optional.empty(),
            "infertest/inter-cell/multi-cell/primary",
            Optional.of(rootWorkspacePath.resolve("fake-infer")));

    // create infertest/inter-cell/multi-cell/secondary sub-workspace
    Path secondaryRootPath = tmp.newFolder().toRealPath().normalize();
    ProjectWorkspace secondary =
        InferHelper.setupWorkspace(
            this, secondaryRootPath, "infertest/inter-cell/multi-cell/secondary");

    // register cells
    registerCell(primary, "secondary", secondary);

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//:cxxbinary")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    // run from primary workspace
    ProcessResult result =
        primary.runBuckBuild(
            InferHelper.getCxxCLIConfigurationArgs(
                rootWorkspacePath.resolve("fake-infer"), Optional.empty(), inputBuildTarget));

    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(primary.getDestPath());
    String reportPath =
        BuildTargetPaths.getGenPath(filesystem, inputBuildTarget, "infer-%s/report.json")
            .toString();
    List<Object> bugs = InferHelper.loadInferReport(primary, reportPath);
    Assert.assertThat(
        "2 bugs expected in " + reportPath + " not found", bugs.size(), Matchers.equalTo(2));
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsEmitsAllBuildRulesInvolvedWhenCacheHit()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());
    String buildTargetName = inputBuildTarget.getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allInvolvedTargets = buildLog.getAllTargets();
    assertEquals(1, allInvolvedTargets.size()); // Only main target should be fetched from cache
    for (BuildTarget bt : allInvolvedTargets) {
      buildLog.assertTargetWasFetchedFromCache(bt);
    }

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt"))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt")));

    BuildTarget analyzeMainTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps#infer-analyze");
    BuildTarget analyzeDepOneTarget =
        BuildTargetFactory.newInstance("//foo:diamond_dep_one#default,infer-analyze");
    BuildTarget analyzeDepTwoTarget =
        BuildTargetFactory.newInstance("//foo:diamond_dep_two#default,infer-analyze");
    BuildTarget analyzeSimpleLibTarget =
        BuildTargetFactory.newInstance("//foo:simple_lib#default,infer-analyze");

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget =
        BuildTargetFactory.newInstance(
            "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);

    Path basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            InferLogLine.fromBuildTarget(
                    analyzeMainTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem, analyzeMainTarget, "infer-analysis-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    srcWithDepsTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, srcWithDepsTarget, "infer-out-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    analyzeDepOneTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem, analyzeDepOneTarget, "infer-analysis-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    depOneTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, depOneTarget, "infer-out-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    analyzeDepTwoTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem, analyzeDepTwoTarget, "infer-analysis-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    depTwoTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, depTwoTarget, "infer-out-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    analyzeSimpleLibTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(
                            filesystem, analyzeSimpleLibTarget, "infer-analysis-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    simpleCppTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, simpleCppTarget, "infer-out-%s")))
                .toString());

    assertEquals(expectedOutput, loggedDeps);
  }

  @Test
  public void testInferCaptureAllCxxBinaryWithDiamondDepsEmitsAllBuildRulesInvolvedWhenCacheHit()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());
    String buildTargetName = inputBuildTarget.getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", buildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    ImmutableSet<BuildTarget> allInvolvedTargets = buildLog.getAllTargets();
    for (BuildTarget bt : allInvolvedTargets) {
      buildLog.assertTargetWasFetchedFromCache(bt);
    }

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt"))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt")));

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget =
        BuildTargetFactory.newInstance(
            "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);

    Path basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            InferLogLine.fromBuildTarget(
                    srcWithDepsTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, srcWithDepsTarget, "infer-out-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    depOneTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, depOneTarget, "infer-out-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    depTwoTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, depTwoTarget, "infer-out-%s")))
                .toString(),
            InferLogLine.fromBuildTarget(
                    simpleCppTarget,
                    basePath.resolve(
                        BuildTargetPaths.getGenPath(filesystem, simpleCppTarget, "infer-out-%s")))
                .toString());

    assertEquals(expectedOutput, loggedDeps);
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsHasRuntimeDepsOfAllCaptureRulesWhenCacheHits()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor())
            .getFullyQualifiedName();

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that runtime deps have been fetched from cache as well
     */
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:simple_lib#default,infer-capture-" + sanitize("simple.cpp.o")),
                        "infer-out-%s")
                    .resolve("captured/simple.cpp_captured/simple.cpp.cfg"))));
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:diamond_dep_one#default,infer-capture-"
                                + sanitize("dep_one.c.o")),
                        "infer-out-%s")
                    .resolve("captured/dep_one.c_captured/dep_one.c.cfg"))));
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:diamond_dep_two#default,infer-capture-"
                                + sanitize("dep_two.c.o")),
                        "infer-out-%s")
                    .resolve("captured/dep_two.c_captured/dep_two.c.cfg"))));
    assertTrue(
        "This file was expected to exist because it's declared as runtime dep",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:binary_with_diamond_deps#default,infer-capture-"
                                + sanitize("src_with_deps.c.o")),
                        "infer-out-%s")
                    .resolve("captured/src_with_deps.c_captured/src_with_deps.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryWithUnusedDepsDoesNotRebuildWhenUnusedHeaderChanges()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_unused_header");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor())
            .getFullyQualifiedName();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);

    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), inputBuildTarget, cxxPlatform, cxxBuckConfig);

    BuildTarget simpleOneCppCaptureTarget =
        cxxSourceRuleFactory.createInferCaptureBuildTarget("simple_one.cpp");

    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    /*
     * Check that when the unused-header is changed, no builds are triggered
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("foo/unused_header.h", "int* input", "int* input, int* input2");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    BuckBuildLog.BuildLogEntry simpleOnceCppCaptureTargetEntry =
        buildLog.getLogEntry(simpleOneCppCaptureTarget);

    assertThat(
        simpleOnceCppCaptureTargetEntry.getSuccessType(),
        Matchers.equalTo(Optional.of(BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED)));

    /*
     * Check that when the used-header is changed, then a build is triggered
     */
    workspace.resetBuildLogFile();
    workspace.replaceFileContents("foo/used_header.h", "int* input", "int* input, int* input2");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();
    buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(simpleOneCppCaptureTarget);
  }

  @Test
  public void testInferCxxBinaryWithDiamondDepsEmitsAllTransitiveCaptureRulesOnce()
      throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_diamond_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE_ALL.getFlavor());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt"))));

    Set<String> loggedDeps =
        getUniqueLines(
            workspace.getFileContents(
                BuildTargetPaths.getGenPath(
                    filesystem, inputBuildTarget, "infer-%s/infer-deps.txt")));

    String sanitizedSimpleCpp = sanitize("simple.cpp.o");
    String sanitizedDepOne = sanitize("dep_one.c.o");
    String sanitizedDepTwo = sanitize("dep_two.c.o");
    String sanitizedSrcWithDeps = sanitize("src_with_deps.c.o");
    BuildTarget simpleCppTarget =
        BuildTargetFactory.newInstance(
            "//foo:simple_lib#default,infer-capture-" + sanitizedSimpleCpp);
    BuildTarget depOneTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_one#default,infer-capture-" + sanitizedDepOne);
    BuildTarget depTwoTarget =
        BuildTargetFactory.newInstance(
            "//foo:diamond_dep_two#default,infer-capture-" + sanitizedDepTwo);
    BuildTarget srcWithDepsTarget =
        BuildTargetFactory.newInstance(
            "//foo:binary_with_diamond_deps#default,infer-capture-" + sanitizedSrcWithDeps);

    Path basePath = filesystem.getRootPath().toRealPath();
    Set<String> expectedOutput =
        ImmutableSet.of(
            srcWithDepsTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedSrcWithDeps
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(filesystem, srcWithDepsTarget, "infer-out-%s")),
            depOneTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedDepOne
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(filesystem, depOneTarget, "infer-out-%s")),
            depTwoTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedDepTwo
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(filesystem, depTwoTarget, "infer-out-%s")),
            simpleCppTarget.getFullyQualifiedName()
                + "\t"
                + "[default, infer-capture-"
                + sanitizedSimpleCpp
                + "]\t"
                + basePath.resolve(
                    BuildTargetPaths.getGenPath(filesystem, simpleCppTarget, "infer-out-%s")));

    assertEquals(expectedOutput, loggedDeps);
  }

  @Test
  public void testInferCxxBinarySkipsBlacklistedFiles() throws IOException {
    ProjectWorkspace workspace =
        InferHelper.setupCxxInferWorkspace(this, tmp, Optional.of(".*one\\.c"));
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor())
            .getFullyQualifiedName();

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    // Check that the cfg associated with chain_dep_one.c does not exist
    assertFalse(
        "Cfg file for chain_dep_one.c should not exist",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_one#default,infer-capture-"
                                + sanitize("chain_dep_one.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_one.c_captured/chain_dep_one.c.cfg"))));

    // Check that the remaining files still have their cfgs
    assertTrue(
        "Expected cfg for chain_dep_two.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_two#default,infer-capture-"
                                + sanitize("chain_dep_two.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_two.c_captured/chain_dep_two.c.cfg"))));
    assertTrue(
        "Expected cfg for top_chain.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:binary_with_chain_deps#infer-analyze"),
                        "infer-analysis-%s")
                    .resolve("captured/top_chain.c_captured/top_chain.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryRunsOnAllFilesWhenBlacklistIsNotSpecified() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget = BuildTargetFactory.newInstance("//foo:binary_with_chain_deps");
    String inputBuildTargetName =
        inputBuildTarget
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor())
            .getFullyQualifiedName();

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTargetName).assertSuccess();

    // Check that all cfgs have been created
    assertTrue(
        "Expected cfg for chain_dep_one.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_one#default,infer-capture-"
                                + sanitize("chain_dep_one.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_one.c_captured/chain_dep_one.c.cfg"))));
    assertTrue(
        "Expected cfg for chain_dep_two.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            "//foo:chain_dep_two#default,infer-capture-"
                                + sanitize("chain_dep_two.c.o")),
                        "infer-out-%s")
                    .resolve("captured/chain_dep_two.c_captured/chain_dep_two.c.cfg"))));
    assertTrue(
        "Expected cfg for top_chain.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        filesystem,
                        BuildTargetFactory.newInstance(
                            filesystem.getRootPath(), "//foo:binary_with_chain_deps#infer-analyze"),
                        "infer-analysis-%s")
                    .resolve("captured/top_chain.c_captured/top_chain.c.cfg"))));
  }

  @Test
  public void testInferCxxBinaryWithCachedDepsGetsAllItsTransitiveDeps() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertTargetWasFetchedFromCache(buildTarget);
    }

    /*
     * Check that if the file in the top target changes, then all the transitive deps will be
     * fetched from the cache (even those that are not direct dependencies).
     * Make sure there's the specs file of the dependency that has distance 2 from
     * the binary target.
     */
    String sourceName = "top_chain.c";
    workspace.replaceFileContents("foo/" + sourceName, "*p += 1", "*p += 10");
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    // Check all the buildrules were fetched from the cache (and there's the specs file)
    assertTrue(
        "Expected specs file for func_ret_null() in chain_dep_two.c not found",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem,
                    BuildTargetFactory.newInstance("//foo:chain_dep_two#default,infer-analyze"),
                    "infer-analysis-%s/specs/mockedSpec.specs"))));
  }

  @Test
  public void testInferCxxBinaryMergesAllReportsOfDependencies() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    /*
     * Build the given target and check that it succeeds.
     */
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    String reportPath =
        BuildTargetPaths.getGenPath(filesystem, inputBuildTarget, "infer-%s/report.json")
            .toString();
    List<Object> bugs = InferHelper.loadInferReport(workspace, reportPath);

    // check that the merge step has merged a total of 3 bugs, one for each target
    // (chain_dep_two, chain_dep_one, binary_with_chain_deps)
    Assert.assertThat(
        "3 bugs expected in " + reportPath + " not found", bugs.size(), Matchers.equalTo(3));
  }

  @Test
  public void testInferCxxBinaryWritesSpecsListFilesOfTransitiveDependencies() throws IOException {
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget inputBuildTarget =
        BuildTargetFactory.newInstance("//foo:binary_with_chain_deps")
            .withFlavors(CxxInferEnhancer.InferFlavors.INFER.getFlavor());

    // Build the given target and check that it succeeds.
    workspace.runBuckCommand("build", inputBuildTarget.getFullyQualifiedName()).assertSuccess();

    String specsPathList =
        BuildTargetPaths.getGenPath(
                filesystem,
                inputBuildTarget.withFlavors(
                    CxxInferEnhancer.InferFlavors.INFER_ANALYZE.getFlavor()),
                "infer-analysis-%s/specs_path_list.txt")
            .toString();
    String out = workspace.getFileContents(specsPathList);

    ImmutableList<Path> paths =
        FluentIterable.from(out.split("\n")).transform(input -> new File(input).toPath()).toList();

    assertSame("There must be 2 paths in total", paths.size(), 2);

    for (Path path : paths) {
      assertTrue("Path must be absolute", path.isAbsolute());
      assertTrue("Path must exist", Files.exists(path));
    }
  }

  @Test
  public void testChangingCompilerPathForcesRebuild() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    BuildTarget linkTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());

    // Get the real location of the compiler executable.
    String executable = Platform.detect() == Platform.MACOS ? "clang++" : "g++";
    Path executableLocation =
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get(executable), EnvVariablesProvider.getSystemEnv())
            .orElse(Paths.get("/usr/bin", executable));

    // Write script as faux clang++/g++ binary
    Path firstCompilerPath = tmp.newFolder("path1");
    Path firstCompiler = firstCompilerPath.resolve(executable);
    filesystem.writeContentsToPath(
        "#!/bin/sh\n" + "exec " + executableLocation + " \"$@\"\n", firstCompiler);

    // Write script as slightly different faux clang++/g++ binary
    Path secondCompilerPath = tmp.newFolder("path2");
    Path secondCompiler = secondCompilerPath.resolve(executable);
    filesystem.writeContentsToPath(
        "#!/bin/sh\n"
            + "exec "
            + executableLocation
            + " \"$@\"\n"
            + "# Comment to make hash different.\n",
        secondCompiler);

    // Make the second faux clang++/g++ binary executable
    MostFiles.makeExecutable(secondCompiler);

    // Run two builds, each with different compiler "binaries".  In the first
    // instance, both binaries are in the PATH but the first binary is not
    // marked executable so is not picked up.
    workspace
        .runBuckCommandWithEnvironmentOverridesAndContext(
            workspace.getDestPath(),
            Optional.empty(),
            ImmutableMap.of(
                "PATH",
                firstCompilerPath
                    + pathSeparator
                    + secondCompilerPath
                    + pathSeparator
                    + EnvVariablesProvider.getSystemEnv().get("PATH")),
            "build",
            target.getFullyQualifiedName())
        .assertSuccess();

    workspace.resetBuildLogFile();

    // Now, make the first faux clang++/g++ binary executable.  In this second
    // instance, both binaries are still in the PATH but the first binary is
    // now marked executable and so is picked up; causing a rebuild.
    MostFiles.makeExecutable(firstCompiler);

    workspace
        .runBuckCommandWithEnvironmentOverridesAndContext(
            workspace.getDestPath(),
            Optional.empty(),
            ImmutableMap.of(
                "PATH",
                firstCompilerPath
                    + pathSeparator
                    + secondCompilerPath
                    + pathSeparator
                    + EnvVariablesProvider.getSystemEnv().get("PATH")),
            "build",
            target.getFullyQualifiedName())
        .assertSuccess();

    // Make sure the binary change caused a rebuild.
    workspace.getBuildLog().assertTargetBuiltLocally(linkTarget);
  }

  @Test
  public void testLinkMapIsNotCached() throws Exception {
    // Currently we only support Apple platforms for generating link maps.
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargetPaths.getGenPath(filesystem, target, "%s"));

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(true));
  }

  @Test
  public void testLinkMapIsCached() throws Exception {
    // Currently we only support Apple platforms for generating link maps.
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//foo:simple");
    workspace
        .runBuckCommand("build", "-c", "cxx.cache_binaries=true", target.getFullyQualifiedName())
        .assertSuccess();

    Path outputPath = workspace.getPath(BuildTargetPaths.getGenPath(filesystem, target, "%s"));

    /*
     * Check that building after clean will use the cache
     */
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckCommand("build", "-c", "cxx.cache_binaries=true", target.toString())
        .assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetWasFetchedFromCache(target);
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(true));
  }

  @Test
  public void testSimpleCxxBinaryBuilds() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:simple");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform, cxxBuckConfig);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());
    String sourceName = "simple.cpp";
    String sourceFull = "foo/" + sourceName;
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    assertEquals(
        ImmutableSet.<BuildTarget>builder()
            .add(aggregatedDepsTarget, headerSymlinkTreeTarget, compileTarget, binaryTarget, target)
            .build(),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
    buildLog.assertTargetBuiltLocally(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(target, binaryTarget), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binaryTarget);
    buildLog.assertTargetHadMatchingRuleKey(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{}", "{ return 0; }");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.<BuildTarget>builder()
            .add(aggregatedDepsTarget, compileTarget, binaryTarget, headerSymlinkTreeTarget, target)
            .build(),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        Matchers.not(Matchers.equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(sourceFull, "{ return 0; }", "won't compile");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertFailure();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.<BuildTarget>builder()
            .add(aggregatedDepsTarget, compileTarget, binaryTarget, headerSymlinkTreeTarget, target)
            .build(),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(aggregatedDepsTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getStatus(), Matchers.equalTo(BuildRuleStatus.CANCELED));
    assertThat(
        buildLog.getLogEntry(target).getStatus(), Matchers.equalTo(BuildRuleStatus.CANCELED));
  }

  @Test
  public void testSimpleCxxBinaryWithoutHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:simple_without_header").assertFailure();
  }

  @Test
  public void testSimpleCxxBinaryWithHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:simple_with_header");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform, cxxBuckConfig);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());
    String sourceName = "simple_with_header.cpp";
    String headerName = "simple_with_header.h";
    String headerFull = "foo/" + headerName;
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            aggregatedDepsTarget, headerSymlinkTreeTarget, compileTarget, binaryTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
    buildLog.assertTargetBuiltLocally(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(headerFull, "blah = 5", "blah = 6");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTreeTarget, aggregatedDepsTarget, compileTarget, binaryTarget, target),
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingInputRuleKey(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        Matchers.not(Matchers.equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));
  }

  @Test
  public void testSimpleCxxBinaryMissingDependencyOnCxxLibraryWithHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

  @Test
  public void testSimpleCxxBinaryWithDependencyOnCxxLibraryWithHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();

    // Setup variables pointing to the sources and targets of the top-level binary rule.
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(workspace.asCell().getBuckConfig());
    CxxPlatform cxxPlatform = CxxPlatformUtils.build(cxxBuckConfig);
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:binary_with_dep");
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), target, cxxPlatform, cxxBuckConfig);
    BuildTarget binaryTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());
    String sourceName = "foo.cpp";
    BuildTarget compileTarget = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            target, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget aggregatedDepsTarget =
        cxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    // Setup variables pointing to the sources and targets of the library dep.
    BuildTarget depTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//foo:library_with_header");
    CxxSourceRuleFactory depCxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(
            workspace.getDestPath(), depTarget, cxxPlatform, cxxBuckConfig);
    String depSourceName = "bar.cpp";
    String depSourceFull = "foo/" + depSourceName;
    String depHeaderName = "bar.h";
    String depHeaderFull = "foo/" + depHeaderName;
    BuildTarget depCompileTarget = depCxxSourceRuleFactory.createCompileBuildTarget(depSourceName);
    BuildTarget depHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTarget, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget depHeaderExportedSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            depTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());
    BuildTarget depArchiveTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            depTarget, cxxPlatform.getFlavor(), PicType.PDC);
    BuildTarget depAggregatedDepsTarget =
        depCxxSourceRuleFactory.createAggregatedPreprocessDepsBuildTarget();

    ImmutableList.Builder<BuildTarget> builder = ImmutableList.builder();
    builder.add(
        depAggregatedDepsTarget,
        depHeaderSymlinkTreeTarget,
        depHeaderExportedSymlinkTreeTarget,
        depCompileTarget,
        depArchiveTarget,
        depTarget,
        aggregatedDepsTarget,
        headerSymlinkTreeTarget,
        compileTarget,
        binaryTarget,
        target);

    // Do a clean build, verify that it succeeds, and check that all expected targets built
    // successfully.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(
        buildLog.getAllTargets(),
        containsInAnyOrder(builder.build().toArray(new BuildTarget[] {})));
    buildLog.assertTargetBuiltLocally(depHeaderSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(depCompileTarget);
    buildLog.assertTargetBuiltLocally(depArchiveTarget);
    buildLog.assertTargetBuiltLocally(depTarget);
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
    buildLog.assertTargetBuiltLocally(target);

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(depHeaderFull, "int x", "int y");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();

    builder = ImmutableList.builder();
    builder.add(
        depAggregatedDepsTarget,
        depCompileTarget,
        depArchiveTarget,
        depTarget,
        depHeaderSymlinkTreeTarget,
        depHeaderExportedSymlinkTreeTarget,
        headerSymlinkTreeTarget,
        aggregatedDepsTarget,
        compileTarget,
        binaryTarget,
        target);

    assertThat(
        buildLog.getAllTargets(),
        containsInAnyOrder(builder.build().toArray(new BuildTarget[] {})));
    buildLog.assertTargetBuiltLocally(depAggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(depCompileTarget);
    buildLog.assertTargetHadMatchingInputRuleKey(depArchiveTarget);
    buildLog.assertTargetHadMatchingRuleKey(depHeaderSymlinkTreeTarget);
    buildLog.assertTargetHadMatchingInputRuleKey(depHeaderExportedSymlinkTreeTarget);
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget);
    buildLog.assertTargetHadMatchingRuleKey(depTarget);
    buildLog.assertTargetBuiltLocally(aggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(compileTarget);
    assertThat(
        buildLog.getLogEntry(binaryTarget).getSuccessType().get(),
        Matchers.not(Matchers.equalTo(BuildRuleSuccessType.MATCHING_RULE_KEY)));

    // Clear for new build.
    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents(depSourceFull, "x + 5", "x + 6");

    // Check that running a build again makes the source get recompiled and the binary
    // re-linked, but does not cause the header rules to re-run.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();

    builder = ImmutableList.builder();
    builder.add(
        depAggregatedDepsTarget,
        depCompileTarget,
        depArchiveTarget,
        depTarget,
        depHeaderExportedSymlinkTreeTarget,
        depHeaderSymlinkTreeTarget,
        compileTarget,
        binaryTarget,
        target);

    assertThat(
        buildLog.getAllTargets(),
        containsInAnyOrder(builder.build().toArray(new BuildTarget[] {})));
    buildLog.assertTargetHadMatchingRuleKey(depAggregatedDepsTarget);
    buildLog.assertTargetBuiltLocally(depCompileTarget);
    buildLog.assertTargetBuiltLocally(depArchiveTarget);
    buildLog.assertTargetHadMatchingRuleKey(depTarget);
    buildLog.assertTargetHadMatchingRuleKey(compileTarget);
    buildLog.assertTargetBuiltLocally(binaryTarget);
  }

  @Test
  public void testCxxBinaryDepfileBuildWithChangedHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_binary_depfile_build_with_changed_header", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetBuiltLocally("//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("lib2.h", "hello", "world");

    result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetHadMatchingDepfileRuleKey(
        "//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");
  }

  @Test
  public void testCxxBinaryDepfileBuildWithAddedHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_binary_depfile_build_with_added_header", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:bin#binary");
    buildLog.assertTargetBuiltLocally("//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetBuiltLocally("//:lib1#default,static");

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("BUCK", "[\"lib1.h\"]", "[\"lib1.h\", \"lib2.h\"]");

    result = workspace.runBuckCommand("build", "//:bin");
    result.assertSuccess();

    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingInputRuleKey("//:bin#binary");
    buildLog.assertTargetHadMatchingDepfileRuleKey(
        "//:bin#compile-" + sanitize("bin.c.o") + ",default");
    buildLog.assertTargetHadMatchingInputRuleKey("//:lib1#default,static");
  }

  @Test
  public void testCxxBinaryWithGeneratedSourceAndHeader() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//foo:binary_without_dep").assertFailure();
  }

  @Test
  public void testHeaderNamespace() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:test").assertSuccess();
  }

  @Test
  public void resolveHeadersBehindSymlinkTreesInError() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "resolved", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace.writeContentsToPath("#invalid_pragma", "lib2.h");

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    ProcessResult result = workspace.runBuckCommand("build", target.toString());
    result.assertFailure();

    // Verify that the preprocessed source contains no references to the symlink tree used to
    // setup the headers.
    String error = result.getStderr();
    assertThat(
        error,
        Matchers.not(
            Matchers.containsString(filesystem.getBuckPaths().getScratchDir().toString())));
    assertThat(
        error,
        Matchers.not(Matchers.containsString(filesystem.getBuckPaths().getGenDir().toString())));
    assertThat(error, Matchers.containsString("In file included from lib1.h:1"));
    assertThat(error, Matchers.containsString("from bin.h:1"));
    assertThat(error, Matchers.containsString("from bin.cpp:1:"));
    assertThat(error, Matchers.containsString("lib2.h:1:2: error: invalid preprocessing"));
  }

  @Test
  public void ndkCxxPlatforms() throws IOException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    boolean isPriorNdk17 = AssumeAndroidPlatform.isArmAvailable();
    String armAbiString = isPriorNdk17 ? "arm, " : "";
    workspace.writeContentsToPath(
        "[ndk]\n"
            + "  gcc_version = 4.9\n"
            + ("  cpu_abis = " + armAbiString + "armv7, arm64, x86\n")
            + "  app_platform = android-21\n",
        ".buckconfig");

    if (isPriorNdk17) {
      workspace.runBuckCommand("build", "//foo:simple#android-arm").assertSuccess();
    }
    workspace.runBuckCommand("build", "//foo:simple#android-armv7").assertSuccess();
    workspace.runBuckCommand("build", "//foo:simple#android-arm64").assertSuccess();
    workspace.runBuckCommand("build", "//foo:simple#android-x86").assertSuccess();
  }

  @Test
  public void linkerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "linker_flags", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:binary_with_linker_flag").assertFailure("--bad-flag");
    workspace.runBuckBuild("//:binary_with_library_dep").assertSuccess();
    workspace.runBuckBuild("//:binary_with_exported_flags_library_dep").assertFailure("--bad-flag");
    workspace.runBuckBuild("//:binary_with_prebuilt_library_dep").assertFailure("--bad-flag");

    // Build binary that has unresolved symbols.  Normally this would fail, but should work
    // with the proper linker flag.
    switch (Platform.detect()) {
      case MACOS:
        workspace.runBuckBuild("//:binary_with_unresolved_symbols_macos").assertSuccess();
        break;
      case LINUX:
        workspace.runBuckBuild("//:binary_with_unresolved_symbols_linux").assertSuccess();
        break;
        // $CASES-OMITTED$
      default:
        break;
    }
  }

  private void platformLinkerFlags(ProjectWorkspace workspace, String target) {
    workspace.runBuckBuild("//:binary_matches_default_exactly_" + target).assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default_" + target).assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match_" + target);
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("reference"));
    workspace.runBuckBuild("//:binary_with_library_matches_default_" + target).assertSuccess();
    workspace
        .runBuckBuild("//:binary_with_prebuilt_library_matches_default_" + target)
        .assertSuccess();
  }

  @Test
  public void platformLinkerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_linker_flags", tmp);
    workspace.setUp();

    // Build binary that has unresolved symbols.  Normally this would fail, but should work
    // with the proper linker flag.
    switch (Platform.detect()) {
      case MACOS:
        platformLinkerFlags(workspace, "macos");
        break;
      case LINUX:
        platformLinkerFlags(workspace, "linux");
        break;
        // $CASES-OMITTED$
      default:
        break;
    }
  }

  @Test
  public void perFileFlagsUsedForPreprocessing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "preprocessing_per_file_flags", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:bin");
    result.assertSuccess();
  }

  @Test
  public void correctPerFileFlagsUsedForCompilation() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "compiling_per_file_flags", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:working-bin");
    result.assertSuccess();
  }

  @Test
  public void incorrectPerFileFlagsUsedForCompilation() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "compiling_per_file_flags", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:broken-bin");
    result.assertFailure();
  }

  @Test
  public void platformPreprocessorFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("#error"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformCompilerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_compiler_flags", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.allOf(Matchers.containsString("non-void"), Matchers.containsString("function")));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformHeaders() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_headers", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("header.hpp"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void platformSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "platform_sources", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\n  cxxflags = -Wall -Werror", ".buckconfig");
    workspace.runBuckBuild("//:binary_matches_default_exactly").assertSuccess();
    workspace.runBuckBuild("//:binary_matches_default").assertSuccess();
    ProcessResult result = workspace.runBuckBuild("//:binary_no_match");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("answer()"));
    workspace.runBuckBuild("//:binary_with_library_matches_default").assertSuccess();
  }

  @Test
  public void buildABinaryIfACxxLibraryDepOnlyDeclaresHeaders() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_headers_only", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:binary");

    result.assertSuccess();
  }

  @Test
  public void buildABinaryIfACxxBinaryTransitivelyDepOnlyDeclaresHeaders() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_headers_only", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:transitive");
    System.out.println(result.getStdout());
    System.err.println(result.getStderr());

    result.assertSuccess();
  }

  @Test
  public void buildBinaryWithSharedDependencies() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library", tmp);
    workspace.setUp();
    ProcessResult processResult = workspace.runBuckBuild("//:clowny_binary");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.containsString("in the dependencies have the same output filename"));
  }

  @Test
  public void buildBinaryWithPerFileFlags() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "per_file_flags", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckBuild("//:binary");
    result.assertSuccess();
  }

  @Test
  public void runBinaryUsingSharedLinkStyle() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_link_style", tmp);
    workspace.setUp();
    workspace.runBuckCommand("run", "//:bar").assertSuccess();
  }

  @Test
  public void genruleUsingBinaryUsingSharedLinkStyle() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_link_style", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:gen").assertSuccess();
  }

  @Test
  public void shBinaryAsLinker() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();
    workspace.runBuckBuild("-c", "cxx.ld=//:cxx", "//:binary_with_unused_header").assertSuccess();
  }

  @Test
  public void buildBinaryUsingStaticPicLinkStyle() throws IOException {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "static_pic_link_style", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand(
            "build",
            // This should only work (on some architectures) if PIC was used to build all included
            // object files.
            "--config",
            "cxx.cxxldflags=-shared",
            "//:bar")
        .assertSuccess();
  }

  @Test
  public void testStrippedBinaryProducesBothUnstrippedAndStrippedOutputs()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    BuildTarget unstrippedTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget strippedTarget =
        unstrippedTarget.withAppendedFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", strippedTarget.getFullyQualifiedName())
        .assertSuccess();

    Path strippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, strippedTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR), "%s"));
    Path unstrippedPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, unstrippedTarget, "%s"));

    String strippedOut =
        workspace.runCommand("dsymutil", "-s", strippedPath.toString()).getStdout().orElse("");
    String unstrippedOut =
        workspace.runCommand("dsymutil", "-s", unstrippedPath.toString()).getStdout().orElse("");

    assertThat(strippedOut, Matchers.containsStringIgnoringCase("dyld_stub_binder"));
    assertThat(unstrippedOut, Matchers.containsStringIgnoringCase("dyld_stub_binder"));

    assertThat(strippedOut, Matchers.not(Matchers.containsStringIgnoringCase("test.cpp")));
    assertThat(unstrippedOut, Matchers.containsStringIgnoringCase("test.cpp"));
  }

  @Test
  public void testStrippedBinaryCanBeFetchedFromCacheAlone() throws Exception {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));

    BuildTarget strippedTarget =
        BuildTargetFactory.newInstance("//:test")
            .withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());
    BuildTarget unstrippedTarget =
        strippedTarget.withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", strippedTarget.getFullyQualifiedName())
        .assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", strippedTarget.getFullyQualifiedName())
        .assertSuccess();

    Path strippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, strippedTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR), "%s"));
    Path unstrippedPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, unstrippedTarget, "%s"));

    assertThat(Files.exists(strippedPath), Matchers.equalTo(true));
    assertThat(Files.exists(unstrippedPath), Matchers.equalTo(false));
  }

  @Test
  public void stripRuleCanBeMadeUncachable() throws Exception {
    assumeThat(Platform.detect(), oneOf(Platform.LINUX, Platform.MACOS));

    BuildTarget strippedTarget =
        BuildTargetFactory.newInstance("//:test")
            .withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());
    BuildTarget unstrippedTarget =
        strippedTarget.withoutFlavors(StripStyle.FLAVOR_DOMAIN.getFlavors());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace
        .runBuckCommand(
            "build",
            "--config",
            "cxx.cxxflags=-g",
            "--config",
            "cxx.cache_strips=false",
            strippedTarget.getFullyQualifiedName())
        .assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "cxx.cxxflags=-g",
            "--config",
            "cxx.cache_strips=false",
            strippedTarget.getFullyQualifiedName())
        .assertSuccess();

    Path strippedPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, strippedTarget.withAppendedFlavors(CxxStrip.RULE_FLAVOR), "%s"));
    Path unstrippedPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, unstrippedTarget, "%s"));

    // The unstripped path should be materialized because the strip rule is set to not cache.
    assertTrue(Files.exists(strippedPath));
    assertTrue(Files.exists(unstrippedPath));
  }

  @Test
  public void testStrippedBinaryOutputDiffersFromUnstripped() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    BuildTarget unstrippedTarget = BuildTargetFactory.newInstance("//:test");
    BuildTarget strippedTarget =
        unstrippedTarget.withFlavors(StripStyle.DEBUGGING_SYMBOLS.getFlavor());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    ProcessResult strippedResult =
        workspace.runBuckCommand(
            "targets", "--show-output", strippedTarget.getFullyQualifiedName());
    strippedResult.assertSuccess();

    ProcessResult unstrippedResult =
        workspace.runBuckCommand(
            "targets", "--show-output", unstrippedTarget.getFullyQualifiedName());
    unstrippedResult.assertSuccess();

    String strippedOutput = strippedResult.getStdout().split(" ")[1];
    String unstrippedOutput = unstrippedResult.getStdout().split(" ")[1];
    assertThat(strippedOutput, Matchers.not(Matchers.equalTo(unstrippedOutput)));
  }

  @Test
  public void testBuildingWithAndWithoutLinkerMap() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildTarget withoutLinkerMapTarget =
        target.withAppendedFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_namespace", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    workspace
        .runBuckCommand("build", "--config", "cxx.cxxflags=-g", target.getFullyQualifiedName())
        .assertSuccess();

    BuildTarget binaryWithLinkerMap = target;

    Path binaryWithLinkerMapPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, binaryWithLinkerMap, "%s"));
    Path linkerMapPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem, binaryWithLinkerMap, "%s-LinkMap.txt"));
    assertThat(Files.exists(binaryWithLinkerMapPath), Matchers.equalTo(true));
    assertThat(Files.exists(linkerMapPath), Matchers.equalTo(true));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    workspace
        .runBuckCommand(
            "build", "--config", "cxx.cxxflags=-g", withoutLinkerMapTarget.getFullyQualifiedName())
        .assertSuccess();

    BuildTarget binaryWithoutLinkerMap = withoutLinkerMapTarget;

    Path binaryWithoutLinkerMapPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, binaryWithoutLinkerMap, "%s"));
    linkerMapPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem, binaryWithoutLinkerMap, "%s-LinkMap.txt"));
    assertThat(Files.exists(binaryWithoutLinkerMapPath), Matchers.equalTo(true));
    assertThat(Files.exists(linkerMapPath), Matchers.equalTo(false));
  }

  @Test
  public void testDisablingLinkCaching() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "cxx.cache_links=false", "//foo:simple").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.runBuckBuild("-c", "cxx.cache_links=false", "//foo:simple").assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally(
            CxxDescriptionEnhancer.createCxxLinkTarget(
                BuildTargetFactory.newInstance("//foo:simple"), Optional.empty()));
  }

  @Test
  public void testThinArchives() throws IOException {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    assumeTrue(
        cxxPlatform
            .getAr()
            .resolve(ruleResolver, EmptyTargetConfiguration.INSTANCE)
            .supportsThinArchives());
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//foo:binary_with_dep")
        .assertSuccess();
    ImmutableSortedSet<Path> initialObjects =
        findFiles(tmp.getRoot(), tmp.getRoot().getFileSystem().getPathMatcher("glob:**/*.o"));
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//foo:binary_with_dep")
        .assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally(
            CxxDescriptionEnhancer.createCxxLinkTarget(
                BuildTargetFactory.newInstance("//foo:binary_with_dep"), Optional.empty()));
    ImmutableSortedSet<Path> subsequentObjects =
        findFiles(tmp.getRoot(), tmp.getRoot().getFileSystem().getPathMatcher("glob:**/*.o"));
    assertThat(initialObjects, Matchers.equalTo(subsequentObjects));
  }

  /**
   * Tests that, if a file has to be rebuilt, but its header dependencies do not, that the header
   * tree is still generated into the correct location.
   */
  @Test
  public void headersShouldBeSetUpCorrectlyOnRebuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_binary_dep_header_tree_materialize", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace.runBuckBuild("//:bin").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.copyFile("bin.c.new", "bin.c");
    workspace.runBuckBuild("//:bin").assertSuccess();
    BuckBuildLog log = workspace.getBuildLog();
    log.assertTargetBuiltLocally("//:bin#binary");
  }

  /** Tests --config cxx.declared_platforms */
  @Test
  public void testDeclaredPlatforms() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "declared_platforms", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand("query", "-c", "cxx.declared_platforms=my-favorite-platform", "//:simple")
        .assertSuccess();
  }

  @Test
  public void testDeclaredPlatformsWithDefaultPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "declared_platforms", tmp);
    workspace.setUp();
    workspace
        .runBuckCommand("query", "-c", "cxx.declared_platforms=my-favorite-platform", "//:defaults")
        .assertSuccess();

    // Currently failing
    workspace
        .runBuckCommand(
            "query", "-c", "cxx.declared_platforms=my-favorite-platform", "//:default_platform")
        .assertFailure();
  }

  @Test
  public void targetsInPlatformSpecificFlagsDoNotBecomeDependencies() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_in_platform_specific_flags_do_not_become_dependencies", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckBuild(":bin");
    result.assertSuccess();
  }

  @Test
  public void conflictingHeadersBuildFails() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_conflicts", tmp);
    workspace.setUp();
    String errorMsg = workspace.runBuckBuild(":main").assertFailure().getStderr();
    assertTrue(
        errorMsg.contains(
            "has dependencies using headers that can be included using the same path"));
  }

  @Test
  public void conflictingHeadersWithWhitelistSucceeds() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_conflicts", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("-c", "cxx.conflicting_header_basename_whitelist=public.h", ":main")
        .assertSuccess();
  }

  @Test
  public void testLinkMapCreated() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_linkmap", tmp);
    workspace.setUp();
    workspace.runBuckBuild(":binary#linkmap").assertSuccess();
  }

  @Test
  public void testLinkMapNotCreated() throws IOException {
    assumeThat(Platform.detect(), is(Platform.LINUX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_binary_linkmap", tmp);
    workspace.setUp();
    try {
      workspace.runBuckBuild(":binary#linkmap");
    } catch (HumanReadableException e) {
      assertEquals(
          "Linker for target //:binary#linkmap does not support linker maps.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testRunFlavors() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_flavors", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//bin:bin").assertSuccess("build //bin:bin1");
    workspace.runBuckCommand("run", "//bin:bin").assertSuccess("run //bin:bin1");
    workspace.runBuckCommand("build", "//bin:bin#default").assertSuccess("build //bin:bin#default");
    workspace.runBuckCommand("run", "//bin:bin#default").assertSuccess("run //bin:bin#default");
    workspace.runBuckCommand("build", "//bin:bin1").assertSuccess("build //bin:bin1");
    workspace.runBuckCommand("run", "//bin:bin1").assertSuccess("run //bin:bin1");
  }

  private ImmutableSortedSet<Path> findFiles(Path root, PathMatcher matcher) throws IOException {
    ImmutableSortedSet.Builder<Path> files = ImmutableSortedSet.naturalOrder();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file)) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return files.build();
  }

  private static ImmutableSet<String> getUniqueLines(String str) {
    return ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(str));
  }
}
