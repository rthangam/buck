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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDeps;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceTypes;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.ExplicitCxxToolFlags;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.impl.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables.SharedLibrariesBuilder;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

public class HaskellDescriptionUtils {

  private HaskellDescriptionUtils() {}

  static final Flavor GHCI_FLAV = UserFlavor.of("ghci", "Open a ghci session on this target");

  static final Flavor PROF = InternalFlavor.of("prof");
  static final ImmutableList<String> PROF_FLAGS =
      ImmutableList.of("-prof", "-osuf", "p_o", "-hisuf", "p_hi");
  static final ImmutableList<String> DYNAMIC_FLAGS =
      ImmutableList.of("-dynamic", "-osuf", "dyn_o", "-hisuf", "dyn_hi");
  static final ImmutableList<String> PIC_FLAGS =
      ImmutableList.of("-fPIC", "-fexternal-dynamic-refs");

  /**
   * Create a Haskell compile rule that compiles all the given haskell sources in one step and pulls
   * interface files from all transitive haskell dependencies.
   */
  private static HaskellCompileRule createCompileRule(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      ImmutableSet<BuildRule> deps,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<String> flags,
      HaskellSources sources) {

    CxxPlatform cxxPlatform = platform.getCxxPlatform();

    Map<BuildTarget, ImmutableList<String>> depFlags = new TreeMap<>();
    Map<BuildTarget, ImmutableList<SourcePath>> depIncludes = new TreeMap<>();
    ImmutableSortedMap.Builder<String, HaskellPackage> exposedPackagesBuilder =
        ImmutableSortedMap.naturalOrder();
    ImmutableSortedMap.Builder<String, HaskellPackage> packagesBuilder =
        ImmutableSortedMap.naturalOrder();
    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> ruleDeps = empty;
        if (rule instanceof HaskellCompileDep) {
          HaskellCompileDep haskellCompileDep = (HaskellCompileDep) rule;
          ruleDeps = haskellCompileDep.getCompileDeps(platform);
          HaskellCompileInput compileInput =
              haskellCompileDep.getCompileInput(platform, depType, hsProfile);
          depFlags.put(rule.getBuildTarget(), compileInput.getFlags());
          depIncludes.put(rule.getBuildTarget(), compileInput.getIncludes());

          // We add packages from first-order deps as expose modules, and transitively included
          // packages as hidden ones.
          boolean firstOrderDep = deps.contains(rule);
          for (HaskellPackage pkg : compileInput.getPackages()) {
            if (firstOrderDep) {
              exposedPackagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
            } else {
              packagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
            }
          }
        }
        return ruleDeps;
      }
    }.start();

    Collection<CxxPreprocessorInput> cxxPreprocessorInputs =
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, graphBuilder, deps);
    ExplicitCxxToolFlags.Builder toolFlagsBuilder = CxxToolFlags.explicitBuilder();
    PreprocessorFlags.Builder ppFlagsBuilder = PreprocessorFlags.builder();
    toolFlagsBuilder.setPlatformFlags(
        StringArg.from(CxxSourceTypes.getPlatformPreprocessFlags(cxxPlatform, CxxSource.Type.C)));
    for (CxxPreprocessorInput input : cxxPreprocessorInputs) {
      ppFlagsBuilder.addAllIncludes(input.getIncludes());
      ppFlagsBuilder.addAllFrameworkPaths(input.getFrameworks());
      toolFlagsBuilder.addAllRuleFlags(input.getPreprocessorFlags().get(CxxSource.Type.C));
    }
    ppFlagsBuilder.setOtherFlags(toolFlagsBuilder.build());
    PreprocessorFlags ppFlags = ppFlagsBuilder.build();

    ImmutableList<String> compileFlags =
        ImmutableList.<String>builder()
            .addAll(platform.getCompilerFlags())
            .addAll(flags)
            .addAll(Iterables.concat(depFlags.values()))
            .build();

    ImmutableList<SourcePath> includes =
        ImmutableList.copyOf(Iterables.concat(depIncludes.values()));

    ImmutableSortedMap<String, HaskellPackage> exposedPackages = exposedPackagesBuilder.build();
    ImmutableSortedMap<String, HaskellPackage> packages = packagesBuilder.build();

    return HaskellCompileRule.from(
        target,
        projectFilesystem,
        baseParams,
        graphBuilder,
        platform.getCompiler().resolve(graphBuilder, target.getTargetConfiguration()),
        platform.getHaskellVersion(),
        platform.shouldUseArgsfile(),
        compileFlags,
        ppFlags,
        cxxPlatform,
        depType,
        hsProfile,
        main,
        packageInfo,
        includes,
        exposedPackages,
        packages,
        sources,
        CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.C)
            .resolve(graphBuilder, target.getTargetConfiguration()));
  }

  protected static BuildTarget getCompileBuildTarget(
      BuildTarget target,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile) {

    target =
        target.withFlavors(
            platform.getFlavor(),
            InternalFlavor.of("objects-" + depType.toString().toLowerCase().replace('_', '-')));

    if (hsProfile) {
      target = target.withAppendedFlavors(PROF);
    }

    return target;
  }

  public static HaskellCompileRule requireCompileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ImmutableSet<BuildRule> deps,
      HaskellPlatform platform,
      Linker.LinkableDepType depType,
      boolean hsProfile,
      Optional<String> main,
      Optional<HaskellPackageInfo> packageInfo,
      ImmutableList<String> flags,
      HaskellSources srcs) {

    return (HaskellCompileRule)
        graphBuilder.computeIfAbsent(
            getCompileBuildTarget(buildTarget, platform, depType, hsProfile),
            target ->
                HaskellDescriptionUtils.createCompileRule(
                    target,
                    projectFilesystem,
                    params,
                    graphBuilder,
                    deps,
                    platform,
                    depType,
                    hsProfile,
                    main,
                    packageInfo,
                    flags,
                    srcs));
  }

  /**
   * Create a Haskell link rule that links the given inputs to a executable or shared library and
   * pulls in transitive native linkable deps from the given dep roots.
   */
  public static HaskellLinkRule createLinkRule(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      HaskellPlatform platform,
      Linker.LinkType linkType,
      ImmutableList<Arg> linkerFlags,
      Iterable<Arg> linkerInputs,
      Iterable<? extends NativeLinkable> deps,
      ImmutableSet<BuildTarget> linkWholeDeps,
      Linker.LinkableDepType depType,
      Path outputPath,
      Optional<String> soname,
      boolean hsProfile) {

    Tool linker = platform.getLinker().resolve(graphBuilder, target.getTargetConfiguration());

    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Add the base flags from the `.buckconfig` first.
    argsBuilder.addAll(StringArg.from(platform.getLinkerFlags()));

    // Pass in the appropriate flags to link a shared library.
    if (linkType.equals(Linker.LinkType.SHARED)) {
      argsBuilder.addAll(StringArg.from("-shared", "-dynamic"));
      soname.ifPresent(
          name ->
              argsBuilder.addAll(
                  StringArg.from(
                      MoreIterables.zipAndConcat(
                          Iterables.cycle("-optl"),
                          platform
                              .getCxxPlatform()
                              .getLd()
                              .resolve(graphBuilder, target.getTargetConfiguration())
                              .soname(name)))));
    }

    // Add in extra flags passed into this function.
    argsBuilder.addAll(linkerFlags);

    // We pass in the linker inputs and all native linkable deps by prefixing with `-optl` so that
    // the args go straight to the linker, and preserve their order.
    linkerArgsBuilder.addAll(linkerInputs);
    for (NativeLinkable nativeLinkable :
        NativeLinkables.getNativeLinkables(
            platform.getCxxPlatform(), graphBuilder, deps, depType)) {
      NativeLinkable.Linkage link = nativeLinkable.getPreferredLinkage(platform.getCxxPlatform());
      NativeLinkableInput input =
          nativeLinkable.getNativeLinkableInput(
              platform.getCxxPlatform(),
              NativeLinkables.getLinkStyle(link, depType),
              linkWholeDeps.contains(nativeLinkable.getBuildTarget()),
              graphBuilder,
              target.getTargetConfiguration());
      linkerArgsBuilder.addAll(input.getArgs());
    }

    // Since we use `-optl` to pass all linker inputs directly to the linker, the haskell linker
    // will complain about not having any input files.  So, create a dummy archive with an empty
    // module and pass that in normally to work around this.
    BuildTarget emptyModuleTarget = target.withAppendedFlavors(InternalFlavor.of("empty-module"));
    WriteFile emptyModule =
        graphBuilder.addToIndex(
            new WriteFile(
                emptyModuleTarget,
                projectFilesystem,
                "module Unused where",
                BuildTargetPaths.getGenPath(projectFilesystem, emptyModuleTarget, "%s/Unused.hs"),
                /* executable */ false));
    HaskellCompileRule emptyCompiledModule =
        graphBuilder.addToIndex(
            createCompileRule(
                target.withAppendedFlavors(InternalFlavor.of("empty-compiled-module")),
                projectFilesystem,
                baseParams,
                graphBuilder,
                // TODO(agallagher): We shouldn't need any deps to compile an empty module, but ghc
                // implicitly tries to load the prelude and in some setups this is provided via a
                // Buck dependency.
                RichStream.from(deps)
                    .filter(BuildRule.class)
                    .toImmutableSortedSet(Ordering.natural()),
                platform,
                depType,
                hsProfile,
                Optional.empty(),
                Optional.empty(),
                ImmutableList.of(),
                HaskellSources.builder()
                    .putModuleMap("Unused", emptyModule.getSourcePathToOutput())
                    .build()));
    BuildTarget emptyArchiveTarget = target.withAppendedFlavors(InternalFlavor.of("empty-archive"));
    Archive emptyArchive =
        graphBuilder.addToIndex(
            Archive.from(
                emptyArchiveTarget,
                projectFilesystem,
                graphBuilder,
                platform.getCxxPlatform(),
                "libempty.a",
                emptyCompiledModule.getObjects(),
                /* cacheable */ true));
    argsBuilder.add(SourcePathArg.of(emptyArchive.getSourcePathToOutput()));

    ImmutableList<Arg> args = argsBuilder.build();
    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return graphBuilder.addToIndex(
        new HaskellLinkRule(
            target,
            projectFilesystem,
            baseParams
                .withDeclaredDeps(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(BuildableSupport.getDepsCollection(linker, graphBuilder))
                        .addAll(
                            Stream.of(args, linkerArgs)
                                .flatMap(Collection::stream)
                                .flatMap(arg -> BuildableSupport.getDeps(arg, graphBuilder))
                                .iterator())
                        .build())
                .withoutExtraDeps(),
            linker,
            outputPath,
            args,
            linkerArgs,
            platform.shouldCacheLinks(),
            platform.shouldUseArgsfile()));
  }

  /** Accumulate parse-time deps needed by Haskell descriptions in depsBuilder. */
  public static void getParseTimeDeps(
      TargetConfiguration targetConfiguration,
      Iterable<HaskellPlatform> platforms,
      ImmutableCollection.Builder<BuildTarget> depsBuilder) {
    RichStream.from(platforms)
        .forEach(
            platform -> {

              // Since this description generates haskell link/compile/package rules, make sure the
              // parser includes deps for these tools.
              depsBuilder.addAll(platform.getCompiler().getParseTimeDeps(targetConfiguration));
              depsBuilder.addAll(platform.getLinker().getParseTimeDeps(targetConfiguration));
              depsBuilder.addAll(platform.getPackager().getParseTimeDeps(targetConfiguration));

              // We use the C/C++ linker's Linker object to find out how to pass in the soname, so
              // just add all C/C++ platform parse time deps.
              depsBuilder.addAll(
                  CxxPlatforms.getParseTimeDeps(targetConfiguration, platform.getCxxPlatform()));
            });
  }

  /** Give a rule that will result in a ghci session for the target */
  public static HaskellGhciRule requireGhciRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      HaskellPlatform platform,
      CxxBuckConfig cxxBuckConfig,
      ImmutableSortedSet<BuildTarget> argDeps,
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> argPlatformDeps,
      SourceSortedSet argSrcs,
      ImmutableSortedSet<BuildTarget> argPreloadDeps,
      PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> argPlatformPreloadDeps,
      ImmutableList<String> argCompilerFlags,
      Optional<BuildTarget> argGhciBinDep,
      Optional<SourcePath> argGhciInit,
      ImmutableList<SourcePath> argExtraScriptTemplates,
      boolean hsProfile) {
    ImmutableSet.Builder<BuildRule> depsBuilder = ImmutableSet.builder();
    depsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(argDeps)
            .addPlatformDeps(argPlatformDeps)
            .build()
            .get(graphBuilder, platform.getCxxPlatform()));
    ImmutableSet<BuildRule> deps = depsBuilder.build();

    ImmutableSet.Builder<BuildRule> preloadDepsBuilder = ImmutableSet.builder();
    preloadDepsBuilder.addAll(
        CxxDeps.builder()
            .addDeps(argPreloadDeps)
            .addPlatformDeps(argPlatformPreloadDeps)
            .build()
            .get(graphBuilder, platform.getCxxPlatform()));
    ImmutableSet<BuildRule> preloadDeps = preloadDepsBuilder.build();

    // Haskell visitor
    ImmutableSet.Builder<HaskellPackage> haskellPackages = ImmutableSet.builder();
    ImmutableSet.Builder<HaskellPackage> prebuiltHaskellPackages = ImmutableSet.builder();
    ImmutableSet.Builder<HaskellPackage> firstOrderHaskellPackages = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<BuildRule> haskellVisitor =
        new AbstractBreadthFirstTraversal<BuildRule>(deps) {
          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {
            ImmutableSet.Builder<BuildRule> traverse = ImmutableSet.builder();
            if (rule instanceof HaskellLibrary || rule instanceof PrebuiltHaskellLibrary) {
              HaskellCompileDep haskellRule = (HaskellCompileDep) rule;
              // Always use pic packages so we can use `+RTS -xp -RTS` to load
              // the objects anywhere in the address space without the
              // limitation of lower 2G address space.
              HaskellCompileInput ci =
                  haskellRule.getCompileInput(
                      platform, Linker.LinkableDepType.STATIC_PIC, hsProfile);

              if (params.getBuildDeps().contains(rule)) {
                firstOrderHaskellPackages.addAll(ci.getPackages());
              }

              if (rule instanceof HaskellLibrary) {
                haskellPackages.addAll(ci.getPackages());
              } else if (rule instanceof PrebuiltHaskellLibrary) {
                prebuiltHaskellPackages.addAll(ci.getPackages());
              }

              traverse.addAll(haskellRule.getCompileDeps(platform));
            }

            return traverse.build();
          }
        };
    haskellVisitor.start();

    // Build the omnibus composition spec.
    HaskellGhciOmnibusSpec omnibusSpec =
        HaskellGhciDescription.getOmnibusSpec(
            buildTarget,
            platform.getCxxPlatform(),
            graphBuilder,
            NativeLinkables.getNativeLinkableRoots(
                RichStream.from(deps).filter(NativeLinkable.class).toImmutableList(),
                n ->
                    n instanceof HaskellLibrary || n instanceof PrebuiltHaskellLibrary
                        ? Optional.of(
                            n.getNativeLinkableExportedDepsForPlatform(
                                platform.getCxxPlatform(), graphBuilder))
                        : Optional.empty()),
            // The preloaded deps form our excluded roots, which we need to keep them separate from
            // the omnibus library so that they can be `LD_PRELOAD`ed early.
            RichStream.from(preloadDeps)
                .filter(NativeLinkable.class)
                .collect(ImmutableMap.toImmutableMap(NativeLinkable::getBuildTarget, l -> l)));

    // Add an -rpath to the omnibus for shared library dependencies
    Path symlinkRelDir = HaskellGhciDescription.getSoLibsRelDir(buildTarget);
    ImmutableList.Builder<Arg> extraLinkFlags = ImmutableList.builder();
    extraLinkFlags.addAll(
        StringArg.from(
            Linkers.iXlinker(
                "-rpath",
                String.format(
                    "%s/%s",
                    platform
                        .getCxxPlatform()
                        .getLd()
                        .resolve(graphBuilder, buildTarget.getTargetConfiguration())
                        .origin(),
                    symlinkRelDir.toString()))));

    // Construct the omnibus shared library.
    BuildRule omnibusSharedObject =
        HaskellGhciDescription.requireOmnibusSharedObject(
            cellPathResolver,
            buildTarget,
            projectFilesystem,
            graphBuilder,
            platform.getCxxPlatform(),
            cxxBuckConfig,
            omnibusSpec.getBody().values(),
            omnibusSpec.getDeps().values(),
            extraLinkFlags.build());

    // Build up a map of all transitive shared libraries the the monolithic omnibus library depends
    // on (basically, stuff we couldn't statically link in).  At this point, this should *not* be
    // pulling in any excluded deps.
    SharedLibrariesBuilder sharedLibsBuilder = new SharedLibrariesBuilder();
    ImmutableMap<BuildTarget, NativeLinkable> transitiveDeps =
        NativeLinkables.getTransitiveNativeLinkables(
            platform.getCxxPlatform(), graphBuilder, omnibusSpec.getDeps().values());
    transitiveDeps.values().stream()
        // Skip statically linked libraries.
        .filter(l -> l.getPreferredLinkage(platform.getCxxPlatform()) != Linkage.STATIC)
        .forEach(l -> sharedLibsBuilder.add(platform.getCxxPlatform(), l, graphBuilder));
    ImmutableSortedMap<String, SourcePath> sharedLibs = sharedLibsBuilder.build();

    // Build up a set of all transitive preload libs, which are the ones that have been "excluded"
    // from the omnibus link.  These are the ones we need to LD_PRELOAD.
    SharedLibrariesBuilder preloadLibsBuilder = new SharedLibrariesBuilder();
    omnibusSpec.getExcludedTransitiveDeps().values().stream()
        // Don't include shared libs for static libraries -- except for preload roots, which we
        // always link dynamically.
        .filter(
            l ->
                l.getPreferredLinkage(platform.getCxxPlatform()) != Linkage.STATIC
                    || omnibusSpec.getExcludedRoots().containsKey(l.getBuildTarget()))
        .forEach(l -> preloadLibsBuilder.add(platform.getCxxPlatform(), l, graphBuilder));
    ImmutableSortedMap<String, SourcePath> preloadLibs = preloadLibsBuilder.build();

    HaskellSources srcs = HaskellSources.from(buildTarget, graphBuilder, platform, "srcs", argSrcs);

    return HaskellGhciRule.from(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        srcs,
        argCompilerFlags,
        argGhciBinDep.map(
            target -> Objects.requireNonNull(graphBuilder.getRule(target).getSourcePathToOutput())),
        argGhciInit,
        omnibusSharedObject,
        sharedLibs,
        preloadLibs,
        firstOrderHaskellPackages.build(),
        haskellPackages.build(),
        prebuiltHaskellPackages.build(),
        hsProfile,
        platform.getGhciScriptTemplate().get(),
        argExtraScriptTemplates,
        platform.getGhciIservScriptTemplate().get(),
        platform.getGhciBinutils().get(),
        platform.getGhciGhc().get(),
        platform.getGhciIServ().get(),
        platform.getGhciIServProf().get(),
        platform.getGhciLib().get(),
        platform.getGhciCxx().get(),
        platform.getGhciCc().get(),
        platform.getGhciCpp().get(),
        platform.getGhciPackager().get());
  }
}
