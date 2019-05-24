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

package com.facebook.buck.android;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/**
 * A BuildRule for stripping (removing inessential information from executable binary programs and
 * object files) binaries.
 */
public class StripLinkable extends ModernBuildRule<StripLinkable.Impl> {

  public StripLinkable(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool stripTool,
      SourcePath sourcePathToStrip,
      String strippedObjectName) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(stripTool, sourcePathToStrip, strippedObjectName));
  }

  /** internal buildable implementation */
  static class Impl implements Buildable {

    @AddToRuleKey private final Tool stripTool;
    @AddToRuleKey private final SourcePath sourcePathToStrip;
    @AddToRuleKey private final OutputPath output;

    Impl(Tool stripTool, SourcePath sourcePathToStrip, String strippedObjectName) {
      this.stripTool = stripTool;
      this.sourcePathToStrip = sourcePathToStrip;
      this.output = new OutputPath(strippedObjectName);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      SourcePathResolver sourcePathResolver = buildContext.getSourcePathResolver();
      Path destination = outputPathResolver.resolvePath(output);
      return ImmutableList.of(
          new StripStep(
              filesystem.getRootPath(),
              stripTool.getEnvironment(sourcePathResolver),
              stripTool.getCommandPrefix(sourcePathResolver),
              ImmutableList.of("--strip-unneeded"),
              sourcePathResolver.getAbsolutePath(sourcePathToStrip),
              destination));
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }
}
