/*
 * Copyright 2018-present Facebook, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.CompositeArg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.WriteToFileArg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class StringWithMacrosConverterTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:rule");
  private static final CellPathResolver CELL_ROOTS =
      TestCellPathResolver.get(new FakeProjectFilesystem());
  private static final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS = ImmutableList.of(new LocationMacroExpander());

  @Test
  public void noMacros() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, graphBuilder, MACRO_EXPANDERS);
    assertThat(
        converter.convert(StringWithMacrosUtils.format("something")),
        Matchers.equalTo(StringArg.of("something")));
  }

  @Test
  public void macro() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, graphBuilder, MACRO_EXPANDERS);
    assertThat(
        converter.convert(
            StringWithMacrosUtils.format("%s", LocationMacro.of(genrule.getBuildTarget()))),
        Matchers.equalTo(
            SourcePathArg.of(Preconditions.checkNotNull(genrule.getSourcePathToOutput()))));
  }

  @Test
  public void macroAndString() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, graphBuilder, MACRO_EXPANDERS);
    assertThat(
        converter.convert(
            StringWithMacrosUtils.format("--foo=%s", LocationMacro.of(genrule.getBuildTarget()))),
        Matchers.equalTo(
            CompositeArg.of(
                ImmutableList.of(
                    StringArg.of("--foo="),
                    SourcePathArg.of(
                        Preconditions.checkNotNull(genrule.getSourcePathToOutput()))))));
  }

  @Test
  public void sanitization() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(TARGET)
            .setCellPathResolver(CELL_ROOTS)
            .setActionGraphBuilder(graphBuilder)
            .setExpanders(MACRO_EXPANDERS)
            .setSanitizer(s -> "something else")
            .build();
    assertThat(
        converter.convert(StringWithMacrosUtils.format("something")),
        Matchers.equalTo(SanitizedArg.create(s -> "something else", "something")));
  }

  @Test
  public void outputToFileMacro() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, graphBuilder, MACRO_EXPANDERS);
    Arg result =
        converter.convert(
            StringWithMacrosUtils.format(
                "%s", MacroContainer.of(LocationMacro.of(genrule.getBuildTarget()), true)));
    assertThat(result, Matchers.instanceOf(WriteToFileArg.class));
  }
}
