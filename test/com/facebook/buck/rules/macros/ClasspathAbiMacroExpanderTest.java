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

package com.facebook.buck.rules.macros;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ExportFileBuilder;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class ClasspathAbiMacroExpanderTest {
  private ClasspathAbiMacroExpander expander;
  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    this.expander = new ClasspathAbiMacroExpander();
    this.filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void testShouldIncludeARuleIfNothingIsGiven() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule rule =
        getLibraryBuilder("//cheese:cake")
            .addSrc(Paths.get("Example.java")) // Force a jar to be created
            .build(graphBuilder, filesystem);

    assertExpandsTo(
        rule,
        graphBuilder,
        String.format(
            "%s" + File.separator + "%s",
            filesystem.getRootPath(),
            new File("buck-out/gen/cheese/cake#class-abi/cake-abi.jar").toPath()));
  }

  @Test
  public void testShouldIncludeTransitiveDependencies() throws Exception {
    TargetNode<?> depNode =
        getLibraryBuilder("//exciting:dep").addSrc(Paths.get("Dep.java")).build();

    TargetNode<?> ruleNode =
        getLibraryBuilder("//exciting:target")
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);

    BuildRule rule = graphBuilder.requireRule(ruleNode.getBuildTarget());

    assertExpandsTo(
        rule,
        graphBuilder,
        String.format(
            "%s" + File.separator + "%s" + File.pathSeparatorChar + "%s" + File.separator + "%s",
            filesystem.getRootPath(),
            new File("buck-out/gen/exciting/dep#class-abi/dep-abi.jar").toPath(),
            filesystem.getRootPath(),
            new File("buck-out/gen/exciting/target#class-abi/target-abi.jar").toPath()));
  }

  private JavaLibraryBuilder getLibraryBuilder(String target) {
    return JavaLibraryBuilder.createBuilder(
        BuildTargetFactory.newInstance(filesystem.getRootPath(), target), filesystem);
  }

  @Test(expected = MacroException.class)
  public void testShouldThrowAnExceptionWhenRuleToExpandDoesNotHaveAClasspath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule rule =
        new ExportFileBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//cheese:peas"))
            .setSrc(FakeSourcePath.of("some-file.jar"))
            .build(graphBuilder);

    expander.expand(graphBuilder, rule);
  }

  @Test
  public void testExtractRuleKeyAppendables() throws Exception {
    TargetNode<?> depNode =
        getLibraryBuilder("//exciting:dep").addSrc(Paths.get("Dep.java")).build();
    TargetNode<?> ruleNode =
        getLibraryBuilder("//exciting:target")
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);

    BuildRule rule = graphBuilder.requireRule(ruleNode.getBuildTarget());

    BuildTarget forTarget = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellRoots = createCellRoots(filesystem);
    ClasspathAbiMacro classpathAbiMacro = ClasspathAbiMacro.of(rule.getBuildTarget());
    Arg ruleKeyAppendables =
        expander.expandFrom(forTarget, cellRoots, graphBuilder, classpathAbiMacro);

    ImmutableList<String> deps =
        BuildableSupport.deriveDeps(ruleKeyAppendables, graphBuilder)
            .map(BuildRule::getFullyQualifiedName)
            .collect(ImmutableList.toImmutableList());

    assertThat(
        deps,
        Matchers.equalTo(
            ImmutableList.of("//exciting:dep#class-abi", "//exciting:target#class-abi")));
  }

  private void assertExpandsTo(
      BuildRule rule, ActionGraphBuilder graphBuilder, String expectedClasspath)
      throws MacroException {
    String classpath =
        Arg.stringify(expander.expand(graphBuilder, rule), graphBuilder.getSourcePathResolver());

    assertEquals(expectedClasspath, classpath);
  }
}
