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

package com.facebook.buck.features.go;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class GoTestIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  public ProjectWorkspace workspace;

  @Before
  public void ensureGoIsAvailable() {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "go_test", tmp);
    workspace.setUp();
  }

  @Test
  public void testGoTest() throws IOException {
    // This test should pass.
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success");
    result1.assertSuccess();
    workspace.resetBuildLogFile();

    // This test should fail.
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-failure");
    result2.assertTestFailure();

    assertThat(
        "`buck test` should fail because TestAdd2() failed.",
        result2.getStderr(),
        containsString("TestAdd2"));
    assertThat(
        "`buck test` should print out error message",
        result2.getStderr(),
        containsString("1 + 2 != 3"));
  }

  @Test
  public void testGoTestAfterChange() throws IOException {
    // This test should pass.
    workspace.runBuckCommand("test", "//:test-success").assertSuccess();

    workspace.replaceFileContents("buck_base/base.go", "n1 + n2", "n1 + n2 + 1");
    workspace.runBuckCommand("test", "//:test-success").assertTestFailure();

    workspace.replaceFileContents("buck_base/base.go", "n1 + n2 + 1", "n1 + n2 * 1");
    workspace.runBuckCommand("test", "//:test-success").assertSuccess();
  }

  @Ignore
  @Test
  public void testGoInternalTest() {
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success-internal");
    result1.assertSuccess();
  }

  @Test
  public void testWithResources() {
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-with-resources");
    result1.assertSuccess();

    // no external runner. No symlink should be created
    Path input = workspace.resolve("buck-out/gen/test-with-resources#test-main/testdata/input");
    assertFalse(Files.exists(input));
  }

  @Test
  public void testWithResourcesAndExternalRunner() throws IOException {
    ProcessResult result1 =
        workspace.runBuckCommand(
            "build",
            "--config",
            "test.external_runner=fake/bin/fake_runner",
            "//:test-with-resources");
    result1.assertSuccess();

    assertIsRegularCopy(
        workspace.resolve("buck-out/gen/test-with-resources#test-main/testdata/input"),
        workspace.resolve("testdata/input"));
  }

  @Test
  public void testWithResourcesDirectoryAndExternalRunner() throws IOException {
    ProcessResult result1 =
        workspace.runBuckCommand(
            "build",
            "--config",
            "test.external_runner=fake/bin/fake_runner",
            "//:test-with-resources-directory");
    result1.assertSuccess();

    assertIsRegularCopy(
        workspace.resolve("buck-out/gen/test-with-resources-directory#test-main/testdata/input"),
        workspace.resolve("testdata/input"));
  }

  @Test
  public void testWithResourcesDirectory2LevelAndExternalRunner() throws IOException {
    ProcessResult result1 =
        workspace.runBuckCommand(
            "build",
            "--config",
            "test.external_runner=fake/bin/fake_runner",
            "//:test-with-resources-2directory");
    result1.assertSuccess();

    assertIsRegularCopy(
        workspace.resolve(
            "buck-out/gen/test-with-resources-2directory#test-main/testdata/level2/input"),
        workspace.resolve("testdata/level2/input"));
  }

  @Test
  public void testWithResourcesDirectory2Level2ResourcesAndExternalRunner() throws IOException {
    ProcessResult result1 =
        workspace.runBuckCommand(
            "build",
            "--config",
            "test.external_runner=fake/bin/fake_runner",
            "//:test-with-resources-2directory-2resources");
    result1.assertSuccess();

    assertIsRegularCopy(
        workspace.resolve(
            "buck-out/gen/test-with-resources-2directory-2resources#test-main/testdata/level2/input"),
        workspace.resolve("testdata/level2/input"));
    assertIsRegularCopy(
        workspace.resolve(
            "buck-out/gen/test-with-resources-2directory-2resources#test-main/testdata/level2bis/input"),
        workspace.resolve("testdata/level2bis/input"));
  }

  @Test
  public void testGoInternalTestInTestList() {
    ProcessResult processResult = workspace.runBuckCommand("test", "//:test-success-bad");
    processResult.assertFailure();
  }

  @Test
  public void testGoTestTimeout() {
    ProcessResult result = workspace.runBuckCommand("test", "//:test-spinning");
    result.assertTestFailure("test timed out after 500ms");
  }

  @Test
  public void testGoPanic() {
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-panic");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because TestPanic() failed.",
        result2.getStderr(),
        containsString("TestPanic"));
    assertThat(
        "`buck test` should print out the error message",
        result2.getStderr(),
        containsString("I can't take it anymore"));
  }

  @Test
  public void testSubTests() {
    GoAssumptions.assumeGoVersionAtLeast("1.7.0");
    ProcessResult result = workspace.runBuckCommand("test", "//:subtests");
    result.assertSuccess();
  }

  @Test
  public void testIndirectDeps() {
    ProcessResult result = workspace.runBuckCommand("test", "//add:test-add13");
    result.assertSuccess();
  }

  @Test
  public void testLibWithCgoDeps() {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProcessResult result = workspace.runBuckCommand("test", "//cgo/lib:all_tests");
    result.assertSuccess();
  }

  @Test
  public void testGenRuleAsSrc() {
    ProcessResult result = workspace.runBuckCommand("test", "//genrule_as_src:test");
    result.assertSuccess();
  }

  @Test
  public void testGenRuleWithLibAsSrc() {
    ProcessResult result = workspace.runBuckCommand("test", "//genrule_wtih_lib_as_src:test");
    result.assertSuccess();
  }

  @Test
  public void testHyphen() {
    // This test should pass.
    ProcessResult result = workspace.runBuckCommand("test", "//:test-hyphen");
    result.assertSuccess();
  }

  @Test
  public void testFuncWithPrefixTest() {
    ProcessResult result = workspace.runBuckCommand("test", "//:test-scores");
    result.assertSuccess();
  }

  @Test
  public void testNonprintableCharacterInResult() {
    ProcessResult result = workspace.runBuckCommand("test", "//testOutput:all_tests");
    assertThat(
        "`buck test` should print out the error message",
        result.getStderr(),
        containsString("is not printable"));
    assertFalse(result.getStderr().contains("MalformedInputException"));
  }

  @Test
  public void testGoTestWithEnv() {
    ProcessResult result = workspace.runBuckCommand("test", "//:test-with-env");
    result.assertSuccess();
  }

  @Test
  public void testGoTestWithSystemEnv() throws IOException {
    workspace
        .runBuckdCommand(ImmutableMap.of(), "test", "//:test-with-system-env")
        .assertTestFailure();
    workspace
        .runBuckdCommand(ImmutableMap.of("FOO", "BAR"), "test", "//:test-with-system-env")
        .assertSuccess();
    workspace
        .runBuckdCommand(ImmutableMap.of(), "test", "//:test-with-system-env")
        .assertTestFailure();
  }

  private static void assertIsRegularCopy(Path link, Path target) throws IOException {
    assertTrue(Files.isRegularFile(link));
    assertEquals(Files.readAllLines(link), Files.readAllLines(target));
  }
}
