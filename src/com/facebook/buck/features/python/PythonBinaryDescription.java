/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.features.python;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.impl.WindowsLinker;
import com.facebook.buck.features.python.PythonBuckConfig.PackageStyle;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class PythonBinaryDescription
    implements DescriptionWithTargetGraph<PythonBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<
            PythonBinaryDescription.AbstractPythonBinaryDescriptionArg>,
        VersionRoot<PythonBinaryDescriptionArg> {

  private static final Logger LOG = Logger.get(PythonBinaryDescription.class);

  private final ToolchainProvider toolchainProvider;
  private final PythonBuckConfig pythonBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;

  public PythonBinaryDescription(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.pythonBuckConfig = pythonBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<PythonBinaryDescriptionArg> getConstructorArgType() {
    return PythonBinaryDescriptionArg.class;
  }

  public static BuildTarget getEmptyInitTarget(BuildTarget baseTarget) {
    return baseTarget.withAppendedFlavors(InternalFlavor.of("__init__"));
  }

  public static SourcePath createEmptyInitModule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder) {
    BuildTarget emptyInitTarget = getEmptyInitTarget(buildTarget);
    Path emptyInitPath =
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/__init__.py");
    WriteFile rule =
        graphBuilder.addToIndex(
            new WriteFile(
                emptyInitTarget, projectFilesystem, "", emptyInitPath, /* executable */ false));
    return rule.getSourcePathToOutput();
  }

  public static ImmutableMap<Path, SourcePath> addMissingInitModules(
      ImmutableMap<Path, SourcePath> modules, SourcePath emptyInit) {

    Map<Path, SourcePath> initModules = new LinkedHashMap<>();

    // Insert missing `__init__.py` modules.
    Set<Path> packages = new HashSet<>();
    for (Path module : modules.keySet()) {
      Path pkg = module;
      while ((pkg = pkg.getParent()) != null && !packages.contains(pkg)) {
        Path init = pkg.resolve("__init__.py");
        if (!modules.containsKey(init)) {
          initModules.put(init, emptyInit);
        }
        packages.add(pkg);
      }
    }

    return ImmutableMap.<Path, SourcePath>builder().putAll(modules).putAll(initModules).build();
  }

  private PythonInPlaceBinary createInPlaceBinaryRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      Optional<String> extension,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries,
      PackageStyle packageStyle) {

    // We don't currently support targeting Windows.
    if (cxxPlatform.getLd().resolve(graphBuilder, buildTarget.getTargetConfiguration())
        instanceof WindowsLinker) {
      throw new HumanReadableException(
          "%s: cannot build in-place python binaries for Windows (%s)",
          buildTarget, cxxPlatform.getFlavor());
    }

    // Add in any missing init modules into the python components.
    SourcePath emptyInit = createEmptyInitModule(buildTarget, projectFilesystem, graphBuilder);
    components = components.withModules(addMissingInitModules(components.getModules(), emptyInit));

    BuildTarget linkTreeTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("link-tree"));
    Path linkTreeRoot = BuildTargetPaths.getGenPath(projectFilesystem, linkTreeTarget, "%s");
    SymlinkTree linkTree =
        graphBuilder.addToIndex(
            new SymlinkTree(
                "python_in_place_binary",
                linkTreeTarget,
                projectFilesystem,
                linkTreeRoot,
                ImmutableMap.<Path, SourcePath>builder()
                    .putAll(components.getModules())
                    .putAll(components.getResources())
                    .putAll(components.getNativeLibraries())
                    .build(),
                components.getModuleDirs(),
                graphBuilder));

    return new PythonInPlaceBinary(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        params.getDeclaredDeps(),
        cxxPlatform,
        pythonPlatform,
        mainModule,
        components,
        extension.orElse(pythonBuckConfig.getPexExtension()),
        preloadLibraries,
        pythonBuckConfig.legacyOutputPath(),
        linkTree,
        pythonPlatform.getEnvironment(),
        packageStyle);
  }

  PythonBinary createPackageRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      Optional<String> extension,
      PythonPackageComponents components,
      ImmutableList<String> buildArgs,
      PythonBuckConfig.PackageStyle packageStyle,
      ImmutableSet<String> preloadLibraries) {

    switch (packageStyle) {
      case INPLACE_LITE:
      case INPLACE:
        return createInPlaceBinaryRule(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            extension,
            components,
            preloadLibraries,
            packageStyle);

      case STANDALONE:
        return new PythonPackagedBinary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            params.getDeclaredDeps(),
            pythonPlatform,
            toolchainProvider
                .getByName(PexToolProvider.DEFAULT_NAME, PexToolProvider.class)
                .getPexTool(graphBuilder, buildTarget.getTargetConfiguration()),
            buildArgs,
            pythonBuckConfig
                .getPexExecutor(graphBuilder, buildTarget.getTargetConfiguration())
                .orElse(pythonPlatform.getEnvironment()),
            extension.orElse(pythonBuckConfig.getPexExtension()),
            pythonPlatform.getEnvironment(),
            mainModule,
            components,
            preloadLibraries,
            pythonBuckConfig.shouldCacheBinaries(),
            pythonBuckConfig.legacyOutputPath());

      default:
        throw new IllegalStateException();
    }
  }

  private UnresolvedCxxPlatform getCxxPlatform(
      BuildTarget target, AbstractPythonBinaryDescriptionArg args) {
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();
    return cxxPlatforms
        .getValue(target)
        .orElse(
            args.getCxxPlatform()
                .map(cxxPlatforms::getValue)
                .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform()));
  }

  @Override
  public PythonBinary createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonBinaryDescriptionArg args) {
    if (args.getMain().isPresent() == args.getMainModule().isPresent()) {
      throw new HumanReadableException(
          "%s: must set exactly one of `main_module` and `main`", buildTarget);
    }
    Path baseModule = PythonUtil.getBasePath(buildTarget, args.getBaseModule());

    String mainModule;
    ImmutableMap.Builder<Path, SourcePath> modules = ImmutableMap.builder();
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    // If `main` is set, add it to the map of modules for this binary and also set it as the
    // `mainModule`, otherwise, use the explicitly set main module.
    if (args.getMain().isPresent()) {
      LOG.info(
          "%s: parameter `main` is deprecated, please use `main_module` instead.", buildTarget);
      String mainName =
          graphBuilder.getSourcePathResolver().getSourcePathName(buildTarget, args.getMain().get());
      Path main = baseModule.resolve(mainName);
      modules.put(baseModule.resolve(mainName), args.getMain().get());
      mainModule = PythonUtil.toModuleName(buildTarget, main.toString());
    } else {
      mainModule = args.getMainModule().get();
    }
    // Build up the list of all components going into the python binary.
    PythonPackageComponents binaryPackageComponents =
        PythonPackageComponents.of(
            modules.build(),
            /* resources */ ImmutableMap.of(),
            /* nativeLibraries */ ImmutableMap.of(),
            /* moduleDirs */ ImmutableMultimap.of(),
            /* zipSafe */ args.getZipSafe());

    FlavorDomain<PythonPlatform> pythonPlatforms =
        toolchainProvider
            .getByName(PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.class)
            .getPythonPlatforms();

    // Extract the platforms from the flavor, falling back to the default platforms if none are
    // found.
    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(buildTarget)
            .orElse(
                pythonPlatforms.getValue(
                    args.getPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform =
        getCxxPlatform(buildTarget, args)
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());
    CellPathResolver cellRoots = context.getCellPathResolver();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setActionGraphBuilder(graphBuilder)
            .setExpanders(PythonUtil.MACRO_EXPANDERS)
            .build();
    PythonPackageComponents allPackageComponents =
        PythonUtil.getAllComponents(
            cellRoots,
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            PythonUtil.getDeps(pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps())
                .stream()
                .map(graphBuilder::getRule)
                .collect(ImmutableList.toImmutableList()),
            binaryPackageComponents,
            pythonPlatform,
            cxxBuckConfig,
            cxxPlatform,
            args.getLinkerFlags().stream()
                .map(macrosConverter::convert)
                .collect(ImmutableList.toImmutableList()),
            pythonBuckConfig.getNativeLinkStrategy(),
            args.getPreloadDeps());
    return createPackageRule(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        pythonPlatform,
        cxxPlatform,
        mainModule,
        args.getExtension(),
        allPackageComponents,
        args.getBuildArgs(),
        args.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle()),
        PythonUtil.getPreloadNames(graphBuilder, cxxPlatform, args.getPreloadDeps()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractPythonBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    extraDepsBuilder.addAll(
        getCxxPlatform(buildTarget, constructorArg)
            .getLinkerParseTimeDeps(buildTarget.getTargetConfiguration()));

    if (constructorArg.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle())
        == PythonBuckConfig.PackageStyle.STANDALONE) {
      Optionals.addIfPresent(
          pythonBuckConfig.getPexTarget(buildTarget.getTargetConfiguration()), extraDepsBuilder);
      Optionals.addIfPresent(
          pythonBuckConfig.getPexExecutorTarget(buildTarget.getTargetConfiguration()),
          extraDepsBuilder);
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPythonBinaryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasTests, HasVersionUniverse {
    Optional<SourcePath> getMain();

    Optional<String> getMainModule();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getBaseModule();

    Optional<Boolean> getZipSafe();

    ImmutableList<String> getBuildArgs();

    Optional<String> getPlatform();

    Optional<Flavor> getCxxPlatform();

    Optional<PythonBuckConfig.PackageStyle> getPackageStyle();

    ImmutableSet<BuildTarget> getPreloadDeps();

    ImmutableList<StringWithMacros> getLinkerFlags();

    Optional<String> getExtension();
  }
}
