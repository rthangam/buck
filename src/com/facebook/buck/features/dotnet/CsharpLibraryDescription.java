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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

public class CsharpLibraryDescription
    implements DescriptionWithTargetGraph<CsharpLibraryDescriptionArg> {

  public CsharpLibraryDescription() {}

  @Override
  public Class<CsharpLibraryDescriptionArg> getConstructorArgType() {
    return CsharpLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CsharpLibraryDescriptionArg args) {

    BuildRuleResolver resolver = context.getActionGraphBuilder();
    ImmutableList.Builder<Either<BuildRule, String>> refsAsRules = ImmutableList.builder();
    for (Either<BuildTarget, String> ref : args.getDeps()) {
      if (ref.isLeft()) {
        refsAsRules.add(Either.ofLeft(resolver.getRule(ref.getLeft())));
      } else {
        refsAsRules.add(Either.ofRight(ref.getRight()));
      }
    }

    return new CsharpLibrary(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context
            .getToolchainProvider()
            .getByName(DotnetToolchain.DEFAULT_NAME, DotnetToolchain.class)
            .getCsharpCompiler()
            .resolve(context.getActionGraphBuilder(), buildTarget.getTargetConfiguration()),
        args.getDllName(),
        args.getSrcs(),
        refsAsRules.build(),
        args.getResources(),
        args.getFrameworkVer());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCsharpLibraryDescriptionArg extends CommonDescriptionArg, HasSrcs {
    FrameworkVersion getFrameworkVer();

    ImmutableMap<String, SourcePath> getResources();

    @Value.Default
    default String getDllName() {
      return getName() + ".dll";
    }

    // We may have system-provided references ("System.Core.dll") or other build targets
    ImmutableList<Either<BuildTarget, String>> getDeps();
  }
}
