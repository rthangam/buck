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

package com.facebook.buck.cli;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreStringsForTests;
import com.facebook.buck.util.ThriftRuleKeyDeserializer;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.thrift.TException;
import org.hamcrest.Matchers;
import org.hamcrest.junit.MatcherAssert;
import org.junit.Rule;
import org.junit.Test;

public class BuildCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void justBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    workspace.runBuckBuild("--just-build", "//:bar", "//:foo", "//:ex ample").assertSuccess();
    assertThat(
        workspace.getBuildLog().getAllTargets(),
        Matchers.contains(BuildTargetFactory.newInstance(workspace.getDestPath(), "//:bar")));
  }

  @Test
  public void showOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-output", "//:bar");
    runBuckResult.assertSuccess();
    assertThat(runBuckResult.getStdout(), Matchers.containsString("//:bar buck-out"));
  }

  @Test
  public void showFullOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-full-output", "//:bar");
    runBuckResult.assertSuccess();
    Path expectedRootDirectory = tmp.getRoot();
    String expectedOutputDirectory = expectedRootDirectory.resolve("buck-out/").toString();
    String stdout = runBuckResult.getStdout();
    assertThat(stdout, Matchers.containsString("//:bar "));
    assertThat(stdout, Matchers.containsString(expectedOutputDirectory));
  }

  @Test
  public void showJsonOutput() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("--show-json-output", "//:foo", "//:bar", "//:ex ample");
    runBuckResult.assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            "\"//:bar\" : \"buck-out/gen/bar/bar\",\n  \"//:ex ample\" : \"buck-out/gen/ex ample/example\",\n  \"//:foo\" : \"buck-out/gen/foo/foo\"\n}"));
  }

  @Test
  public void showFullJsonOutput() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build/sub folder", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("--show-full-json-output", "//:bar", "//:foo", "//:ex ample");
    runBuckResult.assertSuccess();
    Path expectedRootDirectory = tmp.getRoot();
    String expectedOutputDirectory = expectedRootDirectory.resolve("buck-out/").toString();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            "{\n  \"//:bar\" : \""
                + expectedOutputDirectory
                + "/gen/bar/bar\",\n  \"//:ex ample\" : \""
                + expectedOutputDirectory
                + "/gen/ex ample/example\",\n  \"//:foo\" : \""
                + expectedOutputDirectory
                + "/gen/foo/foo\"\n}"));
  }

  @Test
  public void showRuleKey() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-rulekey", "//:bar");
    runBuckResult.assertSuccess();

    Pattern pattern = Pattern.compile("\\b[0-9a-f]{5,40}\\b"); // sha
    Matcher shaMatcher = pattern.matcher(runBuckResult.getStdout());
    assertThat(shaMatcher.find(), Matchers.equalTo(true));
    String shaValue = shaMatcher.group();
    assertThat(shaValue.length(), Matchers.equalTo(40));
    assertThat(runBuckResult.getStdout(), Matchers.containsString("//:bar " + shaValue));
  }

  @Test
  public void showRuleKeyAndOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("--show-output", "--show-rulekey", "//:bar");
    runBuckResult.assertSuccess();

    Pattern pattern = Pattern.compile("\\b[0-9a-f]{5,40}\\b"); // sha
    Matcher shaMatcher = pattern.matcher(runBuckResult.getStdout());
    assertThat(shaMatcher.find(), Matchers.equalTo(true));
    String shaValue = shaMatcher.group();
    assertThat(shaValue.length(), Matchers.equalTo(40));
    assertThat(
        runBuckResult.getStdout(), Matchers.containsString("//:bar " + shaValue + " buck-out"));
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatSupportsIt() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path externalOutputs = tmp.newFolder("into-output");
    Path output = externalOutputs.resolve("the_example.jar");
    assertFalse(output.toFile().exists());
    workspace.runBuckBuild("//:example", "--out", output.toString()).assertSuccess();
    assertTrue(output.toFile().exists());

    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("com/example/Example.class");
  }

  @Test
  public void buckBuildAndCopyOutputFileIntoDirectoryWithBuildTargetThatSupportsIt()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path outputDir = tmp.newFolder("into-output");
    assertEquals(0, outputDir.toFile().listFiles().length);
    workspace.runBuckBuild("//:example", "--out", outputDir.toString());
    assertTrue(outputDir.toFile().isDirectory());
    File[] files = outputDir.toFile().listFiles();
    assertEquals(1, files.length);
    assertTrue(Files.isRegularFile(outputDir.resolve("example.jar")));
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatDoesNotSupportIt() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path externalOutputs = tmp.newFolder("into-output");
    Path output = externalOutputs.resolve("pylib.zip");
    assertFalse(output.toFile().exists());
    ProcessResult result = workspace.runBuckBuild("//:example_py", "--out", output.toString());
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "//:example_py does not have an output that is compatible with `buck build --out`"));
  }

  @Test
  public void lastOutputDir() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("-c", "build.create_build_output_symlinks_enabled=true", "//:bar");
    runBuckResult.assertSuccess();
    assertTrue(
        Files.exists(workspace.getBuckPaths().getLastOutputDir().toAbsolutePath().resolve("bar")));
  }

  @Test
  public void lastOutputDirForAppleBundle() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_app_bundle", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "-c", "build.create_build_output_symlinks_enabled=true", "//:DemoApp#dwarf-and-dsym");
    runBuckResult.assertSuccess();
    assertTrue(
        Files.exists(
            workspace.getBuckPaths().getLastOutputDir().toAbsolutePath().resolve("DemoApp.app")));
    assertTrue(
        Files.exists(
            workspace
                .getBuckPaths()
                .getLastOutputDir()
                .toAbsolutePath()
                .resolve("DemoAppBinary#apple-dsym,iphonesimulator-x86_64.dSYM")));
  }

  @Test
  public void writesBinaryRuleKeysToDisk() throws IOException, TException {
    Path logFile = tmp.newFile("out.bin");
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "--show-rulekey", "--rulekeys-log-path", logFile.toAbsolutePath().toString(), "//:bar");
    runBuckResult.assertSuccess();

    List<FullRuleKey> ruleKeys = ThriftRuleKeyDeserializer.readRuleKeys(logFile);
    // Three rules, they could have any number of sub-rule keys and contributors
    assertTrue(ruleKeys.size() >= 3);
    assertTrue(ruleKeys.stream().anyMatch(ruleKey -> ruleKey.name.equals("//:bar")));
  }

  @Test
  public void configuredBuckoutSymlinkinSubdirWorksWithoutCells() throws IOException {
    assumeFalse(Platform.detect() == WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "-c",
            "project.buck_out_compat_link=true",
            "-c",
            "project.buck_out=buck-out/mydir",
            "//:foo",
            "//:bar",
            "//:ex ample");
    runBuckResult.assertSuccess();

    assertTrue(Files.exists(workspace.getPath("buck-out/mydir/bin")));
    assertTrue(Files.exists(workspace.getPath("buck-out/mydir/gen")));

    Path buckOut = workspace.resolve("buck-out");
    assertEquals(
        buckOut.resolve("mydir/bin"),
        buckOut.resolve(Files.readSymbolicLink(buckOut.resolve("bin"))));
    assertEquals(
        buckOut.resolve("mydir/gen"),
        buckOut.resolve(Files.readSymbolicLink(buckOut.resolve("gen"))));
  }

  @Test
  public void enableEmbeddedCellHasOnlyOneBuckOut() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multiple_cell_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("-c", "project.embedded_cell_buck_out_enabled=true", "//main/...");
    runBuckResult.assertSuccess();

    assertTrue(Files.exists(workspace.getPath("buck-out/cells/cxx")));
    assertTrue(Files.exists(workspace.getPath("buck-out/cells/java")));

    assertFalse(Files.exists(workspace.getPath("cxx/buck-out")));
    assertFalse(Files.exists(workspace.getPath("java/buck-out")));
  }

  @Test
  public void testFailsIfNoTargetsProvided() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build");
    result.assertExitCode(null, ExitCode.COMMANDLINE_ERROR);
    MatcherAssert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Must specify at least one build target. See https://buck.build/concept/build_target_pattern.html"));
  }

  @Test
  public void testTargetsInFileFilteredByTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_target_filtering", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "build",
            "--target-platforms",
            "//config:osx_x86_64",
            "--exclude-incompatible-targets",
            "//:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:cat_on_osx");
    workspace.getBuildLog().assertTargetIsAbsent("//:cat_on_linux");

    workspace
        .runBuckCommand(
            "build",
            "--target-platforms",
            "//config:linux_x86_64",
            "--exclude-incompatible-targets",
            "//:")
        .assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:cat_on_linux");
    workspace.getBuildLog().assertTargetIsAbsent("//:cat_on_osx");
  }

  @Test
  public void configurationRulesNotIncludedWhenBuildingUsingPattern() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "project_with_configuration_rules", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", ":").assertSuccess();
    ImmutableSet<BuildTarget> targets = workspace.getBuildLog().getAllTargets();

    assertEquals(1, targets.size());
    assertEquals("//:echo", Iterables.getOnlyElement(targets).toString());
  }

  @Test
  public void testBuildDoesNotFailWhenDepDoesNotMatchTargetPlatformAndChecksAreDisables()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--target-platforms",
            "//config:osx_x86-64",
            "-c",
            "parser.enable_target_compatibility_checks=false",
            "//:lib");
    result.assertSuccess();
  }

  @Test
  public void testBuildFailsWhenDepDoesNotMatchTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("build", "--target-platforms", "//config:osx_x86-64", "//:lib");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Build target //:dep is restricted to constraints "
                + "in \"target_compatible_with\" and \"target_compatible_platforms\" "
                + "that do not match the target platform //config:osx_x86-64.\n"
                + "Target constraints:\nbuck//config/constraints:linux"));
  }

  @Test
  public void testBuildFailsWhenDepCompatiblePlatformDoesNotMatchTargetPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--target-platforms",
            "//config:osx_x86-64",
            "//:lib_with_compatible_platform");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Build target //:dep_with_compatible_platform is restricted to constraints "
                + "in \"target_compatible_with\" and \"target_compatible_platforms\" "
                + "that do not match the target platform //config:osx_x86-64.\n"
                + "Target compatible with platforms:\n//config:linux_x86-64"));
  }

  @Test
  public void testBuildFailsWhenNonConfigurableAttributeUsesSelect() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//invalid:lib");
    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "//invalid:lib: attribute 'targetCompatiblePlatforms' cannot be configured using select"));
  }

  @Test
  public void changingTargetPlatformTriggersRebuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    try (TestContext context = new TestContext()) {
      workspace.runBuckBuild(
          Optional.of(context),
          "--target-platforms",
          "//config:osx_x86-64",
          "//:platform_dependent_genrule");

      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

      workspace.runBuckBuild(
          Optional.of(context),
          "--target-platforms",
          "//config:linux_x86-64",
          "//:platform_dependent_genrule");

      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");
    }
  }

  @Test
  public void platformWithCircularDepTriggersFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild(
            "--target-platforms",
            "//config:platform-with-circular-dep",
            "//:platform_dependent_genrule");

    result.assertFailure();
    MatcherAssert.assertThat(
        result.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "Buck can't handle circular dependencies.\n"
                + "The following circular dependency has been found:\n"
                + "//config:platform-with-circular-dep -> //config:platform-with-circular-dep"));
  }

  @Test
  public void hostOsConstraintsAreResolved() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:platform_dependent_genrule");

    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

    String expected;
    Platform platform = Platform.detect();
    if (platform == Platform.LINUX) {
      expected = "linux";
    } else if (platform == Platform.MACOS) {
      expected = "osx";
    } else {
      expected = "unknown";
    }

    assertEquals(expected, workspace.getFileContents(output).trim());
  }

  @Test
  public void hostOsConstraintsAreResolvedWithCustomPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Platform platform = Platform.detect();
    String hostPlatform =
        (platform == Platform.LINUX) ? "//config:osx_x86-64" : "//config:linux_x86-64";

    Path output =
        workspace.buildAndReturnOutput(
            "//:platform_dependent_genrule", "-c", "build.host_platform=" + hostPlatform);

    workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

    String expected = (platform == Platform.LINUX) ? "osx" : "linux";
    assertEquals(expected, workspace.getFileContents(output).trim());
  }

  @Test
  public void hostCpuConstraintsAreResolved() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:cpu_dependent_genrule");

    workspace.getBuildLog().assertTargetBuiltLocally("//:cpu_dependent_genrule");

    String expected;
    Architecture architecture = Architecture.detect();
    if (architecture == Architecture.X86_64) {
      expected = "x86_64";
    } else {
      expected = "unknown";
    }

    assertEquals(expected, workspace.getFileContents(output).trim());
  }

  @Test
  public void hostCpuConstraintsAreResolvedWithCustomHostPlatforms() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "//:cpu_dependent_genrule", "--target-platforms", "//config:osx_x86-64");

    workspace.getBuildLog().assertTargetBuiltLocally("//:cpu_dependent_genrule");

    assertEquals("x86_64", workspace.getFileContents(output).trim());
  }

  @Test
  public void testBuildSucceedsWhenDepMatchesTargetPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "--target-platforms", "//config:linux_x86-64", "//:lib")
        .assertSuccess();
  }

  @Test
  public void changesInConfigurationRulesAreDetected() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "builds_with_constraints", tmp);
    workspace.setUp();

    try (TestContext context = new TestContext()) {

      Path output =
          workspace.buildAndReturnOutput(
              Optional.of(context),
              "//:platform_dependent_genrule",
              "--target-platforms",
              "//config-change:linux_x86-64");
      String linuxOutput = String.join(" ", Files.readAllLines(output)).trim();
      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

      assertEquals("linux", linuxOutput);

      workspace.writeContentsToPath(
          "platform(\n"
              + "    name = \"linux\",\n"
              + "    constraint_values = [\n"
              + "        \"buck//config/constraints:osx\",\n"
              + "    ],\n"
              + ")\n",
          "config-change/platform-dep/BUCK");

      output =
          workspace.buildAndReturnOutput(
              Optional.of(context),
              "//:platform_dependent_genrule",
              "--target-platforms",
              "//config-change:linux_x86-64");
      String osxOutput = String.join(" ", Files.readAllLines(output)).trim();
      workspace.getBuildLog().assertTargetBuiltLocally("//:platform_dependent_genrule");

      assertEquals("osx", osxOutput);
    }
  }
}
