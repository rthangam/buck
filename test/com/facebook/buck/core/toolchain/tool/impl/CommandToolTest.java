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

package com.facebook.buck.core.toolchain.tool.impl;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CommandToolTest {

  @Test
  public void buildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    Genrule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("output")
            .build(graphBuilder, filesystem);
    SourcePath path = rule.getSourcePathToOutput();

    // Test command and inputs for just passing the source path.
    CommandTool tool = new CommandTool.Builder().addArg(SourcePathArg.of(path)).build();
    assertThat(
        tool.getCommandPrefix(graphBuilder.getSourcePathResolver()),
        Matchers.contains(
            graphBuilder
                .getSourcePathResolver()
                .getAbsolutePath(rule.getSourcePathToOutput())
                .toString()));
    assertThat(BuildableSupport.getDepsCollection(tool, graphBuilder), Matchers.contains(rule));
    assertThat(
        BuildableSupport.deriveInputs(tool).collect(ImmutableList.toImmutableList()),
        Matchers.contains(path));
  }

  @Test
  public void pathSourcePath() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    SourcePath path = PathSourcePath.of(filesystem, Paths.get("output"));

    // Test command and inputs for just passing the source path.
    CommandTool tool = new CommandTool.Builder().addArg(SourcePathArg.of(path)).build();
    assertThat(
        tool.getCommandPrefix(resolver.getSourcePathResolver()),
        Matchers.contains(resolver.getSourcePathResolver().getAbsolutePath(path).toString()));
  }

  @Test
  public void extraInputs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule rule = new FakeBuildRule("//some:target");
    rule.setOutputFile("foo");
    graphBuilder.addToIndex(rule);
    SourcePath path = rule.getSourcePathToOutput();

    CommandTool tool = new CommandTool.Builder().addInputs(ImmutableList.of(path)).build();

    assertThat(BuildableSupport.getDepsCollection(tool, graphBuilder), Matchers.contains(rule));
    assertThat(
        BuildableSupport.deriveInputs(tool).collect(ImmutableList.toImmutableList()),
        Matchers.contains(path));
  }

  @Test
  public void environment() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePath path = FakeSourcePath.of("input");
    CommandTool tool =
        new CommandTool.Builder().addArg("runit").addEnv("PATH", SourcePathArg.of(path)).build();

    assertThat(
        tool.getEnvironment(resolver.getSourcePathResolver()),
        Matchers.hasEntry(Matchers.equalTo("PATH"), Matchers.containsString("input")));
  }

  @Test
  public void environmentBuildTargetSourcePath() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    Genrule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("output")
            .build(graphBuilder, filesystem);
    SourcePath path = rule.getSourcePathToOutput();

    CommandTool tool = new CommandTool.Builder().addEnv("ENV", SourcePathArg.of(path)).build();
    assertThat(
        tool.getEnvironment(graphBuilder.getSourcePathResolver()),
        Matchers.hasEntry(
            "ENV",
            graphBuilder
                .getSourcePathResolver()
                .getAbsolutePath(rule.getSourcePathToOutput())
                .toString()));
    assertThat(BuildableSupport.getDepsCollection(tool, graphBuilder), Matchers.contains(rule));
    assertThat(
        BuildableSupport.deriveInputs(tool).collect(ImmutableList.toImmutableList()),
        Matchers.contains(path));
  }
}
