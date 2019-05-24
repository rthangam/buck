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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.collect.ImmutableSortedSet;
import java.util.function.Consumer;
import org.hamcrest.Matchers;
import org.junit.Test;

public class FrameworkPathArgTest {

  private static class TestFrameworkPathArg extends FrameworkPathArg {
    public TestFrameworkPathArg(FrameworkPath frameworkPath) {
      super(ImmutableSortedSet.of(frameworkPath));
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void testGetDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget)
            .setOut("foo/bar.o")
            .build(graphBuilder, filesystem);

    FrameworkPath sourcePathFrameworkPath =
        FrameworkPath.ofSourcePath(genrule.getSourcePathToOutput());

    FrameworkPathArg sourcePathFrameworkPathArg = new TestFrameworkPathArg(sourcePathFrameworkPath);
    assertThat(
        BuildableSupport.getDepsCollection(sourcePathFrameworkPathArg, graphBuilder),
        Matchers.contains(genrule));
  }
}
