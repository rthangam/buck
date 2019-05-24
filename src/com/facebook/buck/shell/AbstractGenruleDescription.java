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

package com.facebook.buck.shell;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.macros.ClasspathAbiMacroExpander;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MavenCoordinatesMacroExpander;
import com.facebook.buck.rules.macros.QueryOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryPathsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.rules.macros.WorkerMacroArg;
import com.facebook.buck.rules.macros.WorkerMacroExpander;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.util.RichStream;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.immutables.value.Value;

public abstract class AbstractGenruleDescription<T extends AbstractGenruleDescription.CommonArg>
    implements DescriptionWithTargetGraph<T> {

  protected final ToolchainProvider toolchainProvider;
  protected final SandboxExecutionStrategy sandboxExecutionStrategy;
  protected final boolean enableSandbox;

  protected AbstractGenruleDescription(
      ToolchainProvider toolchainProvider,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      boolean enableSandbox) {
    this.toolchainProvider = toolchainProvider;
    this.sandboxExecutionStrategy = sandboxExecutionStrategy;
    this.enableSandbox = enableSandbox;
  }

  protected abstract BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      T args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe);

  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      T args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      String outputFileName) {
    return new Genrule(
        buildTarget,
        projectFilesystem,
        resolver,
        params,
        sandboxExecutionStrategy,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        outputFileName,
        args.getEnableSandbox().orElse(enableSandbox),
        args.getCacheable().orElse(true),
        args.getEnvironmentExpansionSeparator(),
        toolchainProvider.getByNameIfPresent(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
        toolchainProvider.getByNameIfPresent(AndroidNdk.DEFAULT_NAME, AndroidNdk.class),
        toolchainProvider.getByNameIfPresent(
            AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class),
        false);
  }

  /**
   * @return the {@link com.facebook.buck.rules.macros.MacroExpander}s which apply to the macros in
   *     this description.
   */
  protected Optional<ImmutableList<MacroExpander<? extends Macro, ?>>> getMacroHandler(
      @SuppressWarnings("unused") BuildTarget buildTarget,
      @SuppressWarnings("unused") ProjectFilesystem filesystem,
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      TargetGraph targetGraph,
      @SuppressWarnings("unused") T args) {
    return Optional.of(
        ImmutableList.of(
            new ClasspathMacroExpander(),
            new ClasspathAbiMacroExpander(),
            new ExecutableMacroExpander(),
            new WorkerMacroExpander(),
            new LocationMacroExpander(),
            new MavenCoordinatesMacroExpander(),
            new QueryTargetsMacroExpander(Optional.of(targetGraph)),
            new QueryOutputsMacroExpander(Optional.of(targetGraph)),
            new QueryPathsMacroExpander(Optional.of(targetGraph)),
            new QueryTargetsAndOutputsMacroExpander(Optional.of(targetGraph))));
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      T args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    Optional<ImmutableList<MacroExpander<? extends Macro, ?>>> maybeExpanders =
        getMacroHandler(
            buildTarget,
            context.getProjectFilesystem(),
            graphBuilder,
            context.getTargetGraph(),
            args);
    if (maybeExpanders.isPresent()) {
      ImmutableList<MacroExpander<? extends Macro, ?>> expanders = maybeExpanders.get();
      StringWithMacrosConverter converter =
          StringWithMacrosConverter.of(
              buildTarget, context.getCellPathResolver(), graphBuilder, expanders);
      Function<StringWithMacros, Arg> toArg =
          str -> {
            Arg arg = converter.convert(str);
            if (RichStream.from(str.getMacros())
                .map(MacroContainer::getMacro)
                .anyMatch(WorkerMacro.class::isInstance)) {
              arg = WorkerMacroArg.fromStringWithMacros(arg, buildTarget, graphBuilder, str);
            }
            return arg;
          };
      Optional<Arg> cmd = args.getCmd().map(toArg);
      Optional<Arg> bash = args.getBash().map(toArg);
      Optional<Arg> cmdExe = args.getCmdExe().map(toArg);
      return createBuildRule(
          buildTarget,
          context.getProjectFilesystem(),
          params.withExtraDeps(
              Stream.concat(
                      graphBuilder.filterBuildRuleInputs(args.getSrcs().getPaths()).stream(),
                      Stream.of(cmd, bash, cmdExe)
                          .flatMap(RichStream::from)
                          .flatMap(input -> BuildableSupport.getDeps(input, graphBuilder)))
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()))),
          graphBuilder,
          args,
          cmd,
          bash,
          cmdExe);
    }
    return createBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        graphBuilder,
        args,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @SuppressFieldNotInitialized
  public interface CommonArg extends CommonDescriptionArg, HasTests {
    Optional<StringWithMacros> getBash();

    Optional<StringWithMacros> getCmd();

    Optional<StringWithMacros> getCmdExe();

    Optional<String> getType();

    @Value.Default
    default SourceSet getSrcs() {
      return SourceSet.EMPTY;
    }

    Optional<Boolean> getEnableSandbox();

    Optional<String> getEnvironmentExpansionSeparator();

    /**
     * This functionality only exists to get around the lack of extensibility in our current build
     * rule / build file apis. It may go away at some point. Also, make sure that you understand
     * what {@link BuildRule.isCacheable} does with respect to caching if you decide to use this
     * attribute
     */
    Optional<Boolean> getCacheable();
  }
}
