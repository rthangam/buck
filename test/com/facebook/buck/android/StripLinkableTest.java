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
package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleResolver;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.junit.Test;

public class StripLinkableTest {

  @Test
  public void testThatDepsIncludesTheThingsThatDepsShouldInclude() {
    // TODO(cjhopman): This is dumb. We should be able to depend on the framework doing the right
    // thing.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    BuildTarget toolTarget = BuildTargetFactory.newInstance("//:tool");

    FakeBuildRule libraryRule = new FakeBuildRule(libraryTarget);
    FakeBuildRule toolRule = new FakeBuildRule(toolTarget);

    ImmutableMap<BuildTarget, BuildRule> ruleMap =
        ImmutableMap.of(libraryTarget, libraryRule, toolTarget, toolRule);

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder =
        new AbstractBuildRuleResolver() {
          @Override
          public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
            return Optional.ofNullable(ruleMap.get(buildTarget));
          }
        };

    SourcePath libraryPath = DefaultBuildTargetSourcePath.of(libraryTarget);
    Tool stripTool = new HashedFileTool(DefaultBuildTargetSourcePath.of(toolTarget));

    StripLinkable stripRule =
        new StripLinkable(target, filesystem, ruleFinder, stripTool, libraryPath, "somename.so");

    assertEquals(stripRule.getBuildDeps(), ImmutableSortedSet.of(libraryRule, toolRule));
  }
}
