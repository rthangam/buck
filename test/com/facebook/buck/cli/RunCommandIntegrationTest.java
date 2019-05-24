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

package com.facebook.buck.cli;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RunCommandIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    // sh_binary doesn't support Windows.
    assumeThat(Platform.detect(), is(not(WINDOWS)));
  }

  @Test
  public void testRunCommandWithNoArguments() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run");

    result.assertExitCode("missing argument is error", ExitCode.COMMANDLINE_ERROR);
    assertThat(result.getStderr(), containsString("buck run <target> <arg1> <arg2>..."));
    assertThat(result.getStderr(), containsString("no target given to run"));
  }

  @Test
  public void testRunCommandWithNonExistentDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckCommand("run", "//does/not/exist");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString("//does/not/exist:exist references non-existent directory does/not/exist"));
  }

  @Test
  public void testRunCommandWithNonExistentTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//:does_not_exist");

    result.assertExitCode(null, ExitCode.PARSE_ERROR);
    assertThat(
        result.getStderr(),
        containsString("No build file at BUCK when resolving target //:does_not_exist."));
  }

  @Test
  public void testRunCommandWithArguments() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "run",
            "//cmd:command",
            "one_arg",
            workspace.getPath("output").toAbsolutePath().toString());
    result.assertSuccess("buck run should succeed");
    assertThat(result.getStdout(), containsString("SUCCESS"));
    workspace.verify();
  }

  @Test
  public void testRunCommandWithDashArguments() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "run",
            "//cmd:command",
            "--",
            "one_arg",
            workspace.getPath("output").toAbsolutePath().toString());
    result.assertSuccess("buck run should succeed");
    assertThat(result.getStdout(), containsString("SUCCESS"));
    workspace.verify();
  }

  @Test
  public void testRunCommandFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "run-command-failure", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//cmd:command");
    result.assertSpecialExitCode("buck run should propagate failure", ExitCode.BUILD_ERROR);
  }

  @Test
  public void testRunCommandWithDashArgumentsAndFlagFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run-command", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "run",
            "//cmd:command",
            "@extra_options",
            "--",
            "@flagfile",
            workspace.getPath("configured_output").toAbsolutePath().toString());
    result.assertSuccess("buck run should succeed");
    assertThat(result.getStdout(), containsString("CONFIG = baz"));
    assertThat(result.getStdout(), containsString("SUCCESS"));
    assertEquals("@flagfile", workspace.getFileContents("configured_output").trim());
  }
}
