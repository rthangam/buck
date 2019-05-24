/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.DepsBuilder;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class SwiftLibraryDescription
    implements DescriptionWithTargetGraph<SwiftLibraryDescriptionArg>, Flavored {

  static final Flavor SWIFT_COMPANION_FLAVOR = InternalFlavor.of("swift-companion");
  static final Flavor SWIFT_COMPILE_FLAVOR = InternalFlavor.of("swift-compile");

  private static final Set<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(
          SWIFT_COMPANION_FLAVOR, SWIFT_COMPILE_FLAVOR, LinkerMapMode.NO_LINKER_MAP.getFlavor());

  public enum Type implements FlavorConvertible {
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("Swift Library Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;

  public SwiftLibraryDescription(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      SwiftBuckConfig swiftBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.swiftBuckConfig = swiftBuckConfig;
  }

  @Override
  public Class<SwiftLibraryDescriptionArg> getConstructorArgType() {
    return SwiftLibraryDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(
        ImmutableSet.of(
            // Missing: swift-companion
            // Missing: swift-compile
            getCxxPlatforms()));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    ImmutableSet<Flavor> currentUnsupportedFlavors =
        ImmutableSet.copyOf(Sets.filter(flavors, Predicates.not(SUPPORTED_FLAVORS::contains)));
    if (currentUnsupportedFlavors.isEmpty()) {
      return true;
    }
    return getCxxPlatforms().containsAnyOf(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      SwiftLibraryDescriptionArg args) {

    Optional<LinkerMapMode> flavoredLinkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(buildTarget);
    buildTarget =
        LinkerMapMode.removeLinkerMapModeFlavorInTarget(buildTarget, flavoredLinkerMapMode);
    UnflavoredBuildTargetView unflavoredBuildTargetView = buildTarget.getUnflavoredBuildTarget();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, UnresolvedCxxPlatform>> platform =
        getCxxPlatforms().getFlavorAndValue(buildTarget);
    ImmutableSortedSet<Flavor> buildFlavors = buildTarget.getFlavors();
    ImmutableSortedSet<BuildRule> filteredExtraDeps =
        params.getExtraDeps().get().stream()
            .filter(
                input ->
                    !input
                        .getBuildTarget()
                        .getUnflavoredBuildTarget()
                        .equals(unflavoredBuildTargetView))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    params = params.withExtraDeps(filteredExtraDeps);

    SwiftPlatformsProvider swiftPlatformsProvider =
        toolchainProvider.getByName(
            SwiftPlatformsProvider.DEFAULT_NAME, SwiftPlatformsProvider.class);

    FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain =
        swiftPlatformsProvider.getSwiftCxxPlatforms();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    if (!buildFlavors.contains(SWIFT_COMPANION_FLAVOR) && platform.isPresent()) {
      // TODO(cjhopman): This doesn't properly handle parse time deps...
      CxxPlatform cxxPlatform =
          platform.get().getValue().resolve(graphBuilder, buildTarget.getTargetConfiguration());
      Optional<SwiftPlatform> swiftPlatform = swiftPlatformFlavorDomain.getValue(buildTarget);
      if (!swiftPlatform.isPresent()) {
        throw new HumanReadableException("Platform %s is missing swift compiler", cxxPlatform);
      }

      // See if we're building a particular "type" and "platform" of this library, and if so,
      // extract them from the flavors attached to the build target.
      Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
      if (!buildFlavors.contains(SWIFT_COMPILE_FLAVOR) && type.isPresent()) {
        Set<Flavor> flavors = Sets.newHashSet(buildTarget.getFlavors());
        flavors.remove(type.get().getKey());
        BuildTarget target = buildTarget.withFlavors(flavors);
        if (flavoredLinkerMapMode.isPresent()) {
          target = target.withAppendedFlavors(flavoredLinkerMapMode.get().getFlavor());
        }

        switch (type.get().getValue()) {
          case SHARED:
            return createSharedLibraryBuildRule(
                cellRoots,
                projectFilesystem,
                params,
                graphBuilder,
                target,
                swiftPlatform.get(),
                cxxPlatform,
                args.getSoname());
          case STATIC:
          case MACH_O_BUNDLE:
            // TODO(tho@uber.com) create build rule for other types.
        }
        throw new RuntimeException("unhandled library build type");
      }

      // All swift-compile rules of swift-lib deps are required since we need their swiftmodules
      // during compilation.

      // Direct swift dependencies.
      ImmutableSet<SwiftCompile> swiftCompileRules =
          RichStream.from(params.getBuildDeps())
              .filter(SwiftLibrary.class)
              .map(input -> input.requireSwiftCompileRule(cxxPlatform.getFlavor()))
              .toImmutableSet();

      // Implicitly generated swift libraries of apple_library dependencies with swift code.
      ImmutableSet<SwiftCompile> implicitSwiftCompileRules =
          RichStream.from(params.getBuildDeps())
              .filter(CxxLibrary.class)
              .flatMap(
                  input -> {
                    BuildTarget companionTarget =
                        input.getBuildTarget().withAppendedFlavors(SWIFT_COMPANION_FLAVOR);
                    // Note, this is liable to race conditions. The presence or absence of the
                    // companion
                    // rule should be determined by metadata query, not by assumptions.
                    return RichStream.from(
                        graphBuilder
                            .getRuleOptional(companionTarget)
                            .map(
                                companion ->
                                    ((SwiftLibrary) companion)
                                        .requireSwiftCompileRule(cxxPlatform.getFlavor())));
                  })
              .toImmutableSet();

      // Transitive C libraries whose headers might be visible to swift via bridging.

      CxxPreprocessorInput inputs =
          CxxPreprocessorInput.concat(
              CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                  cxxPlatform, graphBuilder, params.getBuildDeps(), x -> true));
      PreprocessorFlags cxxDeps =
          PreprocessorFlags.of(
              Optional.empty(),
              CxxToolFlags.of(),
              RichStream.from(inputs.getIncludes())
                  .filter(
                      headers -> headers.getIncludeType() != CxxPreprocessables.IncludeType.SYSTEM)
                  .toImmutableSet(),
              inputs.getFrameworks());
      Preprocessor preprocessor =
          cxxPlatform.getCpp().resolve(graphBuilder, buildTarget.getTargetConfiguration());

      BuildTarget buildTargetCopy = buildTarget;
      return new SwiftCompile(
          cxxPlatform,
          swiftBuckConfig,
          buildTarget,
          projectFilesystem,
          params.copyAppendingExtraDeps(
              () ->
                  ImmutableSet.<BuildRule>builder()
                      .addAll(swiftCompileRules)
                      .addAll(implicitSwiftCompileRules)
                      .addAll(cxxDeps.getDeps(graphBuilder))
                      // This is only used for generating include args and may not be actually
                      // needed.
                      .addAll(BuildableSupport.getDepsCollection(preprocessor, graphBuilder))
                      .build()),
          swiftPlatform.get().getSwiftc(),
          args.getFrameworks(),
          args.getModuleName().orElse(buildTarget.getShortName()),
          BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s"),
          args.getSrcs(),
          args.getVersion(),
          RichStream.from(args.getCompilerFlags())
              .map(
                  CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                          buildTargetCopy, cellRoots, graphBuilder, cxxPlatform)
                      ::convert)
              .toImmutableList(),
          args.getEnableObjcInterop(),
          args.getBridgingHeader(),
          preprocessor,
          cxxDeps,
          false);
    }

    // Otherwise, we return the generic placeholder of this library.
    buildTarget =
        LinkerMapMode.restoreLinkerMapModeFlavorInTarget(buildTarget, flavoredLinkerMapMode);
    return new SwiftLibrary(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        ImmutableSet.of(),
        swiftPlatformFlavorDomain,
        args.getBridgingHeader(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getSupportedPlatformsRegex(),
        args.getPreferredLinkage().orElse(NativeLinkable.Linkage.ANY));
  }

  private BuildRule createSharedLibraryBuildRule(
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      BuildTarget buildTarget,
      SwiftPlatform swiftPlatform,
      CxxPlatform cxxPlatform,
      Optional<String> soname) {

    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, buildTarget.withoutFlavors(SUPPORTED_FLAVORS), cxxPlatform);
    Path sharedLibOutput =
        CxxDescriptionEnhancer.getSharedLibraryPath(
            projectFilesystem, buildTarget, sharedLibrarySoname);

    SwiftRuntimeNativeLinkable swiftRuntimeLinkable =
        new SwiftRuntimeNativeLinkable(swiftPlatform, buildTarget.getTargetConfiguration());

    BuildTarget requiredBuildTarget =
        buildTarget
            .withoutFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors())
            .withAppendedFlavors(SWIFT_COMPILE_FLAVOR);
    SwiftCompile rule = (SwiftCompile) graphBuilder.requireRule(requiredBuildTarget);

    NativeLinkableInput.Builder inputBuilder =
        NativeLinkableInput.builder()
            .from(
                swiftRuntimeLinkable.getNativeLinkableInput(
                    cxxPlatform,
                    Linker.LinkableDepType.SHARED,
                    graphBuilder,
                    buildTarget.getTargetConfiguration()))
            .addAllArgs(rule.getAstLinkArgs())
            .addAllArgs(rule.getFileListLinkArg());
    return graphBuilder.addToIndex(
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxBuckConfig,
            cxxPlatform,
            projectFilesystem,
            graphBuilder,
            buildTarget,
            Linker.LinkType.SHARED,
            Optional.of(sharedLibrarySoname),
            sharedLibOutput,
            ImmutableList.of(),
            Linker.LinkableDepType.SHARED,
            CxxLinkOptions.of(),
            RichStream.from(params.getBuildDeps())
                .filter(NativeLinkable.class)
                .concat(RichStream.of(swiftRuntimeLinkable))
                .collect(ImmutableSet.toImmutableSet()),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            inputBuilder.build(),
            Optional.empty(),
            cellPathResolver));
  }

  public Optional<BuildRule> createCompanionBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxLibraryDescription.CommonArg args) {
    if (!isSwiftTarget(buildTarget)) {
      boolean hasSwiftSource =
          !SwiftDescriptions.filterSwiftSources(
                  graphBuilder.getSourcePathResolver(), args.getSrcs())
              .isEmpty();
      return hasSwiftSource
          ? Optional.of(
              graphBuilder.requireRule(buildTarget.withAppendedFlavors(SWIFT_COMPANION_FLAVOR)))
          : Optional.empty();
    }

    SwiftLibraryDescriptionArg.Builder delegateArgsBuilder = SwiftLibraryDescriptionArg.builder();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        swiftBuckConfig,
        graphBuilder.getSourcePathResolver(),
        delegateArgsBuilder,
        args,
        buildTarget);
    SwiftLibraryDescriptionArg delegateArgs = delegateArgsBuilder.build();
    if (!delegateArgs.getSrcs().isEmpty()) {
      return Optional.of(
          graphBuilder.addToIndex(createBuildRule(context, buildTarget, params, delegateArgs)));
    } else {
      return Optional.empty();
    }
  }

  public static SwiftCompile createSwiftCompileRule(
      CxxPlatform cxxPlatform,
      SwiftPlatform swiftPlatform,
      SwiftBuckConfig swiftBuckConfig,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      ProjectFilesystem projectFilesystem,
      SwiftLibraryDescriptionArg args,
      Preprocessor preprocessor,
      PreprocessorFlags preprocessFlags,
      boolean importUnderlyingModule) {

    DepsBuilder srcsDepsBuilder = new DepsBuilder(graphBuilder);
    args.getSrcs().forEach(src -> srcsDepsBuilder.add(src));
    BuildRuleParams paramsWithSrcDeps = params.copyAppendingExtraDeps(srcsDepsBuilder.build());

    return new SwiftCompile(
        cxxPlatform,
        swiftBuckConfig,
        buildTarget,
        projectFilesystem,
        paramsWithSrcDeps,
        swiftPlatform.getSwiftc(),
        args.getFrameworks(),
        args.getModuleName().orElse(buildTarget.getShortName()),
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s"),
        args.getSrcs(),
        args.getVersion(),
        RichStream.from(args.getCompilerFlags())
            .map(
                CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                        buildTarget, cellRoots, graphBuilder, cxxPlatform)
                    ::convert)
            .toImmutableList(),
        args.getEnableObjcInterop(),
        args.getBridgingHeader(),
        preprocessor,
        preprocessFlags,
        importUnderlyingModule);
  }

  public static boolean isSwiftTarget(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(SWIFT_COMPANION_FLAVOR)
        || buildTarget.getFlavors().contains(SWIFT_COMPILE_FLAVOR);
  }

  private FlavorDomain<UnresolvedCxxPlatform> getCxxPlatforms() {
    return toolchainProvider
        .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
        .getUnresolvedCxxPlatforms();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractSwiftLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
    Optional<String> getModuleName();

    ImmutableList<StringWithMacros> getCompilerFlags();

    Optional<String> getVersion();

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getFrameworks();

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getLibraries();

    Optional<Boolean> getEnableObjcInterop();

    Optional<Pattern> getSupportedPlatformsRegex();

    Optional<String> getSoname();

    Optional<SourcePath> getBridgingHeader();

    Optional<NativeLinkable.Linkage> getPreferredLinkage();
  }
}
