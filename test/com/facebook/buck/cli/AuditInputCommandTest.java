/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AuditInputCommandTest {

  private TestConsole console;
  private AuditInputCommand auditInputCommand;
  private CommandRunnerParams params;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  @Before
  public void setUp() throws IOException {
    console = new TestConsole();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.touch(Paths.get("src/com/facebook/AndroidLibraryTwo.java"));
    projectFilesystem.touch(Paths.get("src/com/facebook/TestAndroidLibrary.java"));
    projectFilesystem.touch(Paths.get("src/com/facebook/TestJavaLibrary.java"));
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();

    auditInputCommand = new AuditInputCommand();
    params =
        CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
            executor.get(),
            console,
            cell,
            artifactCache,
            eventBus,
            FakeBuckConfig.builder().build(),
            Platform.detect(),
            EnvVariablesProvider.getSystemEnv(),
            new FakeJavaPackageFinder(),
            Optional.empty());
  }

  @Test
  public void testJsonClassPathOutput() throws IOException {
    ObjectMapper objectMapper = ObjectMappers.legacyCreate();
    String expectedJson =
        Joiner.on("")
            .join(
                "{",
                "\"//:test-android-library\":",
                "[",
                objectMapper.valueToTree(
                    MorePaths.pathWithPlatformSeparators(
                        "src/com/facebook/AndroidLibraryTwo.java")),
                ",",
                objectMapper.valueToTree(
                    MorePaths.pathWithPlatformSeparators(
                        "src/com/facebook/TestAndroidLibrary.java")),
                "],",
                "\"//:test-java-library\":",
                "[",
                objectMapper.valueToTree(
                    MorePaths.pathWithPlatformSeparators("src/com/facebook/TestJavaLibrary.java")),
                "]",
                "}");

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> rootNode =
        JavaLibraryBuilder.createBuilder(rootTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .build();

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    TargetNode<?> libraryNode =
        JavaLibraryBuilder.createBuilder(libraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addSrc(Paths.get("src/com/facebook/AndroidLibraryTwo.java"))
            .addDep(rootTarget)
            .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(rootNode, libraryNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    auditInputCommand.printJsonInputs(params, targetGraph);
    assertEquals(expectedJson, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testNonExistentInputFileThrows() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Target //:test-java-library refers to non-existent input file: "
            + MorePaths.pathWithPlatformSeparators("src/com/facebook/NonExistentFile.java"));

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> rootNode =
        JavaLibraryBuilder.createBuilder(rootTarget)
            .addSrc(Paths.get("src/com/facebook/NonExistentFile.java"))
            .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(rootNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);
    auditInputCommand.printJsonInputs(params, targetGraph);
  }

  @Test
  public void testJsonContainsRulesWithNoFiles() throws IOException {
    final String expectedJson =
        Joiner.on("")
            .join(
                "{",
                "\"//:test-exported-dep\":",
                "[",
                "],",
                "\"//:test-java-library\":",
                "[",
                ObjectMappers.legacyCreate()
                    .valueToTree(
                        MorePaths.pathWithPlatformSeparators(
                            "src/com/facebook/TestJavaLibrary.java")),
                "]",
                "}");

    BuildTarget exportedTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> exportedNode =
        JavaLibraryBuilder.createBuilder(exportedTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//:test-exported-dep");
    TargetNode<?> rootNode =
        JavaLibraryBuilder.createBuilder(rootTarget).addExportedDep(exportedTarget).build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(rootNode, exportedNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    auditInputCommand.printJsonInputs(params, targetGraph);
    assertEquals(expectedJson, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }
}
