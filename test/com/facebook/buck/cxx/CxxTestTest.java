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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeTestRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.Test;

public class CxxTestTest {

  private static final Optional<Long> TEST_TIMEOUT_MS = Optional.of(24L);

  private abstract static class FakeCxxTest extends CxxTest {

    private static final BuildTarget buildTarget = BuildTargetFactory.newInstance("//:target");

    private static BuildRuleParams createBuildParams() {
      return TestBuildRuleParams.create();
    }

    public FakeCxxTest() {
      super(
          buildTarget,
          new FakeProjectFilesystem(),
          createBuildParams(),
          new CommandTool.Builder().build(),
          ImmutableMap.of(),
          ImmutableList.of(),
          ImmutableSortedSet.of(),
          ImmutableSet.of(),
          unused2 -> ImmutableSortedSet.of(),
          ImmutableSet.of(),
          ImmutableSet.of(),
          /* runTestSeparately */ false,
          TEST_TIMEOUT_MS);
    }

    @Override
    protected ImmutableList<String> getShellCommand(SourcePathResolver resolver, Path output) {
      return ImmutableList.of();
    }

    @Override
    protected ImmutableList<TestResultSummary> parseResults(
        Path exitCode, Path output, Path results) {
      return ImmutableList.of();
    }
  }

  @Test
  public void runTests() {
    ImmutableList<String> command = ImmutableList.of("hello", "world");

    FakeCxxTest cxxTest =
        new FakeCxxTest() {

          @Override
          public SourcePath getSourcePathToOutput() {
            return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("output"));
          }

          @Override
          protected ImmutableList<String> getShellCommand(
              SourcePathResolver resolver, Path output) {
            return command;
          }

          @Override
          public Tool getExecutableCommand() {
            CommandTool.Builder builder = new CommandTool.Builder();
            command.forEach(builder::addArg);
            return builder.build();
          }
        };

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestRunningOptions options =
        TestRunningOptions.builder().setTestSelectorList(TestSelectorList.empty()).build();
    ImmutableList<Step> actualSteps =
        cxxTest.runTests(
            executionContext,
            options,
            FakeBuildContext.NOOP_CONTEXT,
            FakeTestRule.NOOP_REPORTING_CALLBACK);

    CxxTestStep cxxTestStep =
        new CxxTestStep(
            new FakeProjectFilesystem(),
            command,
            ImmutableMap.of(),
            cxxTest.getPathToTestExitCode(),
            cxxTest.getPathToTestOutput(),
            TEST_TIMEOUT_MS);

    assertEquals(cxxTestStep, Iterables.getLast(actualSteps));
  }

  @Test
  public void interpretResults() throws Exception {
    Path expectedExitCode = Paths.get("output");
    Path expectedOutput = Paths.get("output");
    Path expectedResults = Paths.get("results");

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeCxxTest cxxTest =
        new FakeCxxTest() {

          @Override
          public SourcePath getSourcePathToOutput() {
            return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("output"));
          }

          @Override
          protected Path getPathToTestExitCode() {
            return expectedExitCode;
          }

          @Override
          protected Path getPathToTestOutput() {
            return expectedOutput;
          }

          @Override
          protected Path getPathToTestResults() {
            return expectedResults;
          }

          @Override
          protected ImmutableList<TestResultSummary> parseResults(
              Path exitCode, Path output, Path results) {
            assertEquals(expectedExitCode, exitCode);
            assertEquals(expectedOutput, output);
            assertEquals(expectedResults, results);
            return ImmutableList.of();
          }
        };
    graphBuilder.addToIndex(cxxTest);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    Callable<TestResults> result =
        cxxTest.interpretTestResults(
            executionContext,
            graphBuilder.getSourcePathResolver(),
            /* isUsingTestSelectors */ false);
    result.call();
  }
}
