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

package com.facebook.buck.jvm.java;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.base.Functions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JavacStepTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void successfulCompileDoesNotSendStdoutAndStderrToConsole() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    BuildTarget target = BuildTargetFactory.newInstance(fakeFilesystem.getRootPath(), "//foo:bar");
    JavacStep step =
        new JavacStep(
            fakeJavac,
            javacOptions,
            target,
            buildRuleResolver.getSourcePathResolver(),
            fakeFilesystem,
            classpathChecker,
            CompilerParameters.builder().setScratchPaths(target, fakeFilesystem).build(),
            null,
            null);

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    // Note that we don't include stderr in the step result on success.
    assertThat(result, equalTo(StepExecutionResults.SUCCESS));
    assertThat(listener.getLogMessages(), empty());
  }

  @Test
  public void failedCompileSendsStdoutAndStderrToConsole() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    BuildTarget target = BuildTargetFactory.newInstance(fakeFilesystem.getRootPath(), "//foo:bar");
    JavacStep step =
        new JavacStep(
            fakeJavac,
            javacOptions,
            target,
            buildRuleResolver.getSourcePathResolver(),
            fakeFilesystem,
            classpathChecker,
            CompilerParameters.builder().setScratchPaths(target, fakeFilesystem).build(),
            null,
            null);

    FakeProcess fakeJavacProcess = new FakeProcess(1, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    // JavacStep itself writes stdout to the console on error; we expect the Build class to write
    // the stderr stream returned in the StepExecutionResult
    assertThat(result, equalTo(StepExecutionResult.of(1, Optional.of("javac stderr\n"))));
    assertThat(listener.getLogMessages(), equalTo(ImmutableList.of("javac stdout\n")));
  }

  @Test
  public void existingBootclasspathDirSucceeds() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .setBootclasspath("/this-totally-exists")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> true, file -> false, (path, glob) -> ImmutableSet.of());

    BuildTarget target = BuildTargetFactory.newInstance(fakeFilesystem.getRootPath(), "//foo:bar");
    JavacStep step =
        new JavacStep(
            fakeJavac,
            javacOptions,
            target,
            buildRuleResolver.getSourcePathResolver(),
            fakeFilesystem,
            classpathChecker,
            CompilerParameters.builder().setScratchPaths(target, fakeFilesystem).build(),
            null,
            null);

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    StepExecutionResult result = step.execute(executionContext);

    assertThat(result, equalTo(StepExecutionResults.SUCCESS));
    assertThat(listener.getLogMessages(), empty());
  }

  @Test
  public void bootclasspathResolvedToAbsolutePath() {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .setBootclasspath("/this-totally-exists:relative-path")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> true, file -> false, (path, glob) -> ImmutableSet.of());

    BuildTarget target = BuildTargetFactory.newInstance(fakeFilesystem.getRootPath(), "//foo:bar");
    JavacStep step =
        new JavacStep(
            fakeJavac,
            javacOptions,
            target,
            buildRuleResolver.getSourcePathResolver(),
            fakeFilesystem,
            classpathChecker,
            CompilerParameters.builder().setScratchPaths(target, fakeFilesystem).build(),
            null,
            null);

    FakeProcess fakeJavacProcess = new FakeProcess(0, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();

    String description = step.getDescription(executionContext);
    List<String> options =
        Splitter.on(",")
            .trimResults()
            .splitToList(Splitter.on("Delimiter").splitToList(description).get(0));
    assertThat(options, hasItem("-bootclasspath"));
    int bootclasspathIndex = options.indexOf("-bootclasspath");
    String bootclasspath = options.get(bootclasspathIndex + 1);
    assertThat(bootclasspath, not(isEmptyString()));
    for (String path : Splitter.on(File.pathSeparator).split(bootclasspath)) {
      assertTrue(Paths.get(path).isAbsolute());
    }
  }

  @Test
  public void missingBootclasspathDirFailsWithError() throws Exception {
    FakeJavac fakeJavac = new FakeJavac();
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    ProjectFilesystem fakeFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    JavacOptions javacOptions =
        JavacOptions.builder()
            .setLanguageLevelOptions(
                JavacLanguageLevelOptions.builder()
                    .setSourceLevel("8.0")
                    .setTargetLevel("8.0")
                    .build())
            .setBootclasspath("/no-such-dir")
            .build();
    ClasspathChecker classpathChecker =
        new ClasspathChecker(
            "/", ":", Paths::get, dir -> false, file -> false, (path, glob) -> ImmutableSet.of());

    BuildTarget target = BuildTargetFactory.newInstance(fakeFilesystem.getRootPath(), "//foo:bar");
    JavacStep step =
        new JavacStep(
            fakeJavac,
            javacOptions,
            target,
            buildRuleResolver.getSourcePathResolver(),
            fakeFilesystem,
            classpathChecker,
            CompilerParameters.builder().setScratchPaths(target, fakeFilesystem).build(),
            null,
            null);

    FakeProcess fakeJavacProcess = new FakeProcess(1, "javac stdout\n", "javac stderr\n");

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(
                new FakeProcessExecutor(Functions.constant(fakeJavacProcess), new TestConsole()))
            .build();
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    executionContext.getBuckEventBus().register(listener);
    thrown.expectMessage("Bootstrap classpath /no-such-dir contains no valid entries");
    step.execute(executionContext);
  }
}
