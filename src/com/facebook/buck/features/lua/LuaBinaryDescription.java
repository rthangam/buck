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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.Omnibus;
import com.facebook.buck.cxx.OmnibusLibraries;
import com.facebook.buck.cxx.OmnibusLibrary;
import com.facebook.buck.cxx.OmnibusRoot;
import com.facebook.buck.cxx.OmnibusRoots;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.features.python.CxxPythonExtension;
import com.facebook.buck.features.python.PythonBinaryDescription;
import com.facebook.buck.features.python.PythonPackagable;
import com.facebook.buck.features.python.PythonPackageComponents;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class LuaBinaryDescription
    implements DescriptionWithTargetGraph<LuaBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<LuaBinaryDescription.AbstractLuaBinaryDescriptionArg>,
        VersionRoot<LuaBinaryDescriptionArg> {

  private static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;

  public LuaBinaryDescription(ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<LuaBinaryDescriptionArg> getConstructorArgType() {
    return LuaBinaryDescriptionArg.class;
  }

  @VisibleForTesting
  protected static BuildTarget getNativeLibsSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(InternalFlavor.of("native-libs-link-tree"));
  }

  private static Path getNativeLibsSymlinkTreeRoot(
      BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, getNativeLibsSymlinkTreeTarget(target), "%s");
  }

  private static BuildTarget getModulesSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(InternalFlavor.of("modules-link-tree"));
  }

  private static Path getModulesSymlinkTreeRoot(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, getModulesSymlinkTreeTarget(target), "%s");
  }

  private static BuildTarget getPythonModulesSymlinkTreeTarget(BuildTarget target) {
    return target.withAppendedFlavors(InternalFlavor.of("python-modules-link-tree"));
  }

  private static Path getPythonModulesSymlinkTreeRoot(
      BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, getPythonModulesSymlinkTreeTarget(target), "%s");
  }

  private Path getOutputPath(
      BuildTarget target, ProjectFilesystem filesystem, LuaPlatform luaPlatform) {
    return BuildTargetPaths.getGenPath(filesystem, target, "%s" + luaPlatform.getExtension());
  }

  private Iterable<BuildTarget> getNativeStarterDepTargets(LuaPlatform luaPlatform) {
    Optional<BuildTarget> nativeStarterLibrary = luaPlatform.getNativeStarterLibrary();
    return nativeStarterLibrary.isPresent()
        ? ImmutableSet.of(nativeStarterLibrary.get())
        : RichStream.from(luaPlatform.getLuaCxxLibraryTarget())
            .collect(ImmutableSet.toImmutableSet());
  }

  private Starter getStarter(
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      LuaPlatform luaPlatform,
      BuildTarget target,
      Path output,
      StarterType starterType,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      Optional<Path> relativeModulesDir,
      Optional<Path> relativePythonModulesDir,
      Optional<Path> relativeNativeLibsDir) {
    switch (starterType) {
      case PURE:
        if (relativeNativeLibsDir.isPresent()) {
          throw new HumanReadableException(
              "%s: cannot use pure starter with native libraries", baseTarget);
        }
        return LuaScriptStarter.of(
            projectFilesystem,
            baseTarget,
            baseParams,
            graphBuilder,
            graphBuilder.getSourcePathResolver(),
            luaPlatform,
            target,
            output,
            mainModule,
            relativeModulesDir,
            relativePythonModulesDir);
      case NATIVE:
        return NativeExecutableStarter.of(
            projectFilesystem,
            baseTarget,
            baseParams,
            graphBuilder,
            graphBuilder.getSourcePathResolver(),
            cellPathResolver,
            luaPlatform,
            cxxBuckConfig,
            target,
            output,
            mainModule,
            nativeStarterLibrary,
            relativeModulesDir,
            relativePythonModulesDir,
            relativeNativeLibsDir);
    }
    throw new IllegalStateException(
        String.format("%s: unexpected starter type %s", baseTarget, luaPlatform.getStarterType()));
  }

  private StarterType getStarterType(LuaPlatform luaPlatform, boolean mayHaveNativeCode) {
    return luaPlatform
        .getStarterType()
        .orElse(mayHaveNativeCode ? StarterType.NATIVE : StarterType.PURE);
  }

  /** @return the {@link Starter} used to build the Lua binary entry point. */
  private Starter createStarter(
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      LuaPlatform luaPlatform,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      LuaPlatform.PackageStyle packageStyle,
      boolean mayHaveNativeCode) {

    Path output = getOutputPath(baseTarget, projectFilesystem, luaPlatform);
    StarterType starterType = getStarterType(luaPlatform, mayHaveNativeCode);

    // The relative paths from the starter to the various components.
    Optional<Path> relativeModulesDir = Optional.empty();
    Optional<Path> relativePythonModulesDir = Optional.empty();
    Optional<Path> relativeNativeLibsDir = Optional.empty();

    // For in-place binaries, set the relative paths to the symlink trees holding the components.
    if (packageStyle == LuaPlatform.PackageStyle.INPLACE) {
      relativeModulesDir =
          Optional.of(
              output
                  .getParent()
                  .relativize(getModulesSymlinkTreeRoot(baseTarget, projectFilesystem)));
      relativePythonModulesDir =
          Optional.of(
              output
                  .getParent()
                  .relativize(getPythonModulesSymlinkTreeRoot(baseTarget, projectFilesystem)));

      // We only need to setup a native lib link tree if we're using a native starter.
      if (starterType == StarterType.NATIVE) {
        relativeNativeLibsDir =
            Optional.of(
                output
                    .getParent()
                    .relativize(getNativeLibsSymlinkTreeRoot(baseTarget, projectFilesystem)));
      }
    }

    // Build the starter.
    return getStarter(
        cellPathResolver,
        projectFilesystem,
        baseTarget,
        baseParams,
        graphBuilder,
        luaPlatform,
        baseTarget.withAppendedFlavors(
            packageStyle == LuaPlatform.PackageStyle.STANDALONE
                ? InternalFlavor.of("starter")
                : BINARY_FLAVOR),
        packageStyle == LuaPlatform.PackageStyle.STANDALONE
            ? output.resolveSibling(output.getFileName() + "-starter")
            : output,
        starterType,
        nativeStarterLibrary,
        mainModule,
        relativeModulesDir,
        relativePythonModulesDir,
        relativeNativeLibsDir);
  }

  private LuaBinaryPackageComponents getPackageComponentsFromDeps(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      LuaPlatform luaPlatform,
      PythonPlatform pythonPlatform,
      Optional<BuildTarget> nativeStarterLibrary,
      String mainModule,
      LuaPlatform.PackageStyle packageStyle,
      Iterable<BuildRule> deps,
      CellPathResolver cellPathResolver) {

    CxxPlatform cxxPlatform = luaPlatform.getCxxPlatform();

    LuaPackageComponents.Builder builder = LuaPackageComponents.builder();
    OmnibusRoots.Builder omnibusRoots =
        OmnibusRoots.builder(cxxPlatform, ImmutableSet.of(), graphBuilder);

    Map<BuildTarget, NativeLinkable> nativeLinkableRoots = new LinkedHashMap<>();
    Map<BuildTarget, CxxLuaExtension> luaExtensions = new LinkedHashMap<>();
    Map<BuildTarget, CxxPythonExtension> pythonExtensions = new LinkedHashMap<>();

    // Walk the deps to find all Lua packageables and native linkables.
    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      private final ImmutableSet<BuildRule> empty = ImmutableSet.of();

      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        Iterable<BuildRule> deps = empty;
        if (rule instanceof LuaPackageable) {
          LuaPackageable packageable = (LuaPackageable) rule;
          LuaPackageComponents components =
              packageable.getLuaPackageComponents(graphBuilder.getSourcePathResolver());
          LuaPackageComponents.addComponents(builder, components);
          deps = packageable.getLuaPackageDeps(cxxPlatform, graphBuilder);
          if (components.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep : deps) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
        } else if (rule instanceof CxxPythonExtension) {
          CxxPythonExtension extension = (CxxPythonExtension) rule;
          NativeLinkTarget target = extension.getNativeLinkTarget(pythonPlatform);
          pythonExtensions.put(target.getBuildTarget(), (CxxPythonExtension) rule);
          omnibusRoots.addIncludedRoot(target);
        } else if (rule instanceof PythonPackagable) {
          PythonPackagable packageable = (PythonPackagable) rule;
          PythonPackageComponents components =
              packageable.getPythonPackageComponents(pythonPlatform, cxxPlatform, graphBuilder);
          builder.putAllPythonModules(
              MoreMaps.transformKeys(components.getModules(), Object::toString));
          builder.putAllNativeLibraries(
              MoreMaps.transformKeys(components.getNativeLibraries(), Object::toString));
          deps = packageable.getPythonPackageDeps(pythonPlatform, cxxPlatform, graphBuilder);
          if (components.hasNativeCode(cxxPlatform)) {
            for (BuildRule dep : deps) {
              if (dep instanceof NativeLinkable) {
                NativeLinkable linkable = (NativeLinkable) dep;
                nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
                omnibusRoots.addExcludedRoot(linkable);
              }
            }
          }
        } else if (rule instanceof CxxLuaExtension) {
          CxxLuaExtension extension = (CxxLuaExtension) rule;
          luaExtensions.put(extension.getBuildTarget(), extension);
          omnibusRoots.addIncludedRoot(extension);
        } else if (rule instanceof NativeLinkable) {
          NativeLinkable linkable = (NativeLinkable) rule;
          nativeLinkableRoots.put(linkable.getBuildTarget(), linkable);
          omnibusRoots.addPotentialRoot(linkable);
        }
        return deps;
      }
    }.start();

    // Build the starter.
    Starter starter =
        createStarter(
            cellPathResolver,
            projectFilesystem,
            buildTarget,
            baseParams,
            graphBuilder,
            luaPlatform,
            nativeStarterLibrary,
            mainModule,
            packageStyle,
            !nativeLinkableRoots.isEmpty() || !omnibusRoots.isEmpty());
    SourcePath starterPath = null;

    if (luaPlatform.getNativeLinkStrategy() == NativeLinkStrategy.MERGED) {

      // If we're using a native starter, include it in omnibus linking.
      if (starter instanceof NativeExecutableStarter) {
        NativeExecutableStarter nativeStarter = (NativeExecutableStarter) starter;
        omnibusRoots.addIncludedRoot(nativeStarter);
      }

      // Build the omnibus libraries.
      OmnibusRoots roots = omnibusRoots.build();
      OmnibusLibraries libraries =
          Omnibus.getSharedLibraries(
              buildTarget,
              projectFilesystem,
              baseParams,
              cellPathResolver,
              graphBuilder,
              cxxBuckConfig,
              cxxPlatform,
              ImmutableList.of(),
              roots.getIncludedRoots().values(),
              roots.getExcludedRoots().values());

      // Add all the roots from the omnibus link.  If it's an extension, add it as a module.
      for (Map.Entry<BuildTarget, OmnibusRoot> root : libraries.getRoots().entrySet()) {

        // If it's a Lua extension add it as a module.
        CxxLuaExtension luaExtension = luaExtensions.get(root.getKey());
        if (luaExtension != null) {
          builder.putModules(luaExtension.getModule(cxxPlatform), root.getValue().getPath());
          continue;
        }

        // If it's a Python extension, add it as a python module.
        CxxPythonExtension pythonExtension = pythonExtensions.get(root.getKey());
        if (pythonExtension != null) {
          builder.putPythonModules(
              pythonExtension.getModule().toString(), root.getValue().getPath());
          continue;
        }

        // A root named after the top-level target is our native starter.
        if (root.getKey().equals(buildTarget)) {
          starterPath = root.getValue().getPath();
          continue;
        }

        // Otherwise, add it as a native library.
        NativeLinkTarget target =
            Preconditions.checkNotNull(
                roots.getIncludedRoots().get(root.getKey()),
                "%s: linked unexpected omnibus root: %s",
                buildTarget,
                root.getKey());
        NativeLinkTargetMode mode = target.getNativeLinkTargetMode(cxxPlatform);
        String soname =
            Preconditions.checkNotNull(
                mode.getLibraryName().orElse(null),
                "%s: omnibus library for %s was built without soname",
                buildTarget,
                root.getKey());
        builder.putNativeLibraries(soname, root.getValue().getPath());
      }

      // Add all remaining libraries as native libraries.
      for (OmnibusLibrary library : libraries.getLibraries()) {
        builder.putNativeLibraries(library.getSoname(), library.getPath());
      }

    } else {

      // For regular linking, add all Lua extensions as modules and their deps as native linkable
      // roots.
      for (Map.Entry<BuildTarget, CxxLuaExtension> entry : luaExtensions.entrySet()) {
        CxxLuaExtension extension = entry.getValue();
        builder.putModules(extension.getModule(cxxPlatform), extension.getExtension(cxxPlatform));
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                extension.getNativeLinkTargetDeps(cxxPlatform, graphBuilder),
                NativeLinkable::getBuildTarget));
      }

      // Add in native executable deps.
      if (starter instanceof NativeExecutableStarter) {
        NativeExecutableStarter executableStarter = (NativeExecutableStarter) starter;
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                executableStarter.getNativeStarterDeps(), NativeLinkable::getBuildTarget));
      }

      // For regular linking, add all extensions via the package components interface and their
      // python-platform specific deps to the native linkables.
      for (Map.Entry<BuildTarget, CxxPythonExtension> entry : pythonExtensions.entrySet()) {
        PythonPackageComponents components =
            entry.getValue().getPythonPackageComponents(pythonPlatform, cxxPlatform, graphBuilder);
        builder.putAllPythonModules(
            MoreMaps.transformKeys(components.getModules(), Object::toString));
        builder.putAllNativeLibraries(
            MoreMaps.transformKeys(components.getNativeLibraries(), Object::toString));
        nativeLinkableRoots.putAll(
            Maps.uniqueIndex(
                entry
                    .getValue()
                    .getNativeLinkTarget(pythonPlatform)
                    .getNativeLinkTargetDeps(cxxPlatform, graphBuilder),
                NativeLinkable::getBuildTarget));
      }

      // Add shared libraries from all native linkables.
      for (NativeLinkable nativeLinkable :
          NativeLinkables.getTransitiveNativeLinkables(
                  cxxPlatform, graphBuilder, nativeLinkableRoots.values())
              .values()) {
        NativeLinkable.Linkage linkage = nativeLinkable.getPreferredLinkage(cxxPlatform);
        if (linkage != NativeLinkable.Linkage.STATIC) {
          builder.putAllNativeLibraries(
              nativeLinkable.getSharedLibraries(cxxPlatform, graphBuilder));
        }
      }
    }

    // If an explicit starter path override hasn't been set (e.g. from omnibus linking), default to
    // building one directly from the starter.
    if (starterPath == null) {
      starterPath = starter.build();
    }

    return LuaBinaryPackageComponents.of(starterPath, builder.build());
  }

  private SymlinkTree createSymlinkTree(
      BuildTarget linkTreeTarget,
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      Path root,
      ImmutableMap<String, SourcePath> components) {
    return graphBuilder.addToIndex(
        new SymlinkTree(
            "lua_binary",
            linkTreeTarget,
            filesystem,
            root,
            MoreMaps.transformKeys(components, MorePaths.toPathFn(root.getFileSystem())),
            ImmutableMultimap.of(),
            graphBuilder));
  }

  /**
   * @return the native library map with additional entries for library names with the version
   *     suffix stripped (e.g. libfoo.so.1.0 -> libfoo.so) to appease LuaJIT, which wants to load
   *     libraries using the build-time name.
   */
  private ImmutableSortedMap<String, SourcePath> addVersionLessLibraries(
      CxxPlatform cxxPlatform, ImmutableSortedMap<String, SourcePath> libraries) {
    Pattern versionedExtension =
        Pattern.compile(
            Joiner.on("[.\\d]*")
                .join(
                    Iterables.transform(
                        Splitter.on("%s")
                            .split(cxxPlatform.getSharedLibraryVersionedExtensionFormat()),
                        input -> input.isEmpty() ? input : Pattern.quote(input))));
    Map<String, SourcePath> librariesPaths = new HashMap<>();
    for (Map.Entry<String, SourcePath> ent : libraries.entrySet()) {
      String name = ent.getKey();

      if (librariesPaths.containsKey(name) && librariesPaths.get(name) != ent.getValue()) {
        throw new HumanReadableException(
            "Library %s has multiple possible paths: %s and %s",
            name, ent.getValue(), librariesPaths.get(name));
      }

      librariesPaths.put(name, ent.getValue());
      Matcher matcher = versionedExtension.matcher(name);
      String versionLessName = matcher.replaceAll(cxxPlatform.getSharedLibraryExtension());
      if (!versionLessName.equals(ent.getKey()) && !libraries.containsKey(versionLessName)) {
        librariesPaths.put(versionLessName, ent.getValue());
      }
    }
    return ImmutableSortedMap.copyOf(librariesPaths);
  }

  private Tool getInPlaceBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      SourcePath starter,
      LuaPackageComponents components) {
    List<SourcePath> extraInputs = new ArrayList<>();

    SymlinkTree modulesLinkTree =
        graphBuilder.addToIndex(
            createSymlinkTree(
                getModulesSymlinkTreeTarget(buildTarget),
                projectFilesystem,
                graphBuilder,
                getModulesSymlinkTreeRoot(buildTarget, projectFilesystem),
                components.getModules()));

    List<SymlinkTree> pythonModulesLinktree = new ArrayList<>();
    if (!components.getPythonModules().isEmpty()) {
      // Add in any missing init modules into the python components.
      SourcePath emptyInit =
          PythonBinaryDescription.createEmptyInitModule(
              buildTarget, projectFilesystem, graphBuilder);
      extraInputs.add(emptyInit);
      ImmutableMap<String, SourcePath> pythonModules =
          MoreMaps.transformKeys(
              PythonBinaryDescription.addMissingInitModules(
                  MoreMaps.transformKeys(
                      components.getPythonModules(),
                      MorePaths.toPathFn(projectFilesystem.getRootPath().getFileSystem())),
                  emptyInit),
              Object::toString);
      SymlinkTree symlinkTree =
          graphBuilder.addToIndex(
              createSymlinkTree(
                  getPythonModulesSymlinkTreeTarget(buildTarget),
                  projectFilesystem,
                  graphBuilder,
                  getPythonModulesSymlinkTreeRoot(buildTarget, projectFilesystem),
                  pythonModules));
      pythonModulesLinktree.add(symlinkTree);
    }

    List<SymlinkTree> nativeLibsLinktree = new ArrayList<>();
    if (!components.getNativeLibraries().isEmpty()) {
      SymlinkTree symlinkTree =
          graphBuilder.addToIndex(
              createSymlinkTree(
                  getNativeLibsSymlinkTreeTarget(buildTarget),
                  projectFilesystem,
                  graphBuilder,
                  getNativeLibsSymlinkTreeRoot(buildTarget, projectFilesystem),
                  addVersionLessLibraries(cxxPlatform, components.getNativeLibraries())));
      nativeLibsLinktree.add(symlinkTree);
    }

    return new Tool() {
      @AddToRuleKey private final LuaPackageComponents toolComponents = components;
      @AddToRuleKey private final SourcePath toolStarter = starter;

      @AddToRuleKey
      private final NonHashableSourcePathContainer toolModulesLinkTree =
          new NonHashableSourcePathContainer(modulesLinkTree.getSourcePathToOutput());

      @AddToRuleKey
      private final List<NonHashableSourcePathContainer> toolNativeLibsLinkTree =
          nativeLibsLinktree.stream()
              .map(linkTree -> new NonHashableSourcePathContainer(linkTree.getSourcePathToOutput()))
              .collect(ImmutableList.toImmutableList());

      @AddToRuleKey
      private final List<NonHashableSourcePathContainer> toolPythonModulesLinktree =
          pythonModulesLinktree.stream()
              .map(linkTree -> new NonHashableSourcePathContainer(linkTree.getSourcePathToOutput()))
              .collect(ImmutableList.toImmutableList());

      @AddToRuleKey private final List<SourcePath> toolExtraInputs = extraInputs;

      @Override
      public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
        return ImmutableList.of(resolver.getAbsolutePath(starter).toString());
      }

      @Override
      public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
        return ImmutableMap.of();
      }
    };
  }

  private Tool getStandaloneBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      LuaPlatform luaPlatform,
      SourcePath starter,
      String mainModule,
      LuaPackageComponents components) {
    Path output = getOutputPath(buildTarget, projectFilesystem, luaPlatform);

    Tool lua = luaPlatform.getLua().resolve(graphBuilder, buildTarget.getTargetConfiguration());
    Tool packager =
        luaPlatform.getPackager().resolve(graphBuilder, buildTarget.getTargetConfiguration());

    LuaStandaloneBinary binary =
        graphBuilder.addToIndex(
            new LuaStandaloneBinary(
                buildTarget.withAppendedFlavors(BINARY_FLAVOR),
                projectFilesystem,
                params
                    .withDeclaredDeps(
                        ImmutableSortedSet.<BuildRule>naturalOrder()
                            .addAll(graphBuilder.filterBuildRuleInputs(starter))
                            .addAll(components.getDeps(graphBuilder))
                            .addAll(BuildableSupport.getDepsCollection(lua, graphBuilder))
                            .addAll(BuildableSupport.getDepsCollection(packager, graphBuilder))
                            .build())
                    .withoutExtraDeps(),
                packager,
                output,
                starter,
                components,
                mainModule,
                luaPlatform.shouldCacheBinaries()));

    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(binary.getSourcePathToOutput()))
        .build();
  }

  private Tool getBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      LuaPlatform luaPlatform,
      String mainModule,
      SourcePath starter,
      LuaPackageComponents components,
      LuaPlatform.PackageStyle packageStyle) {
    switch (packageStyle) {
      case STANDALONE:
        return getStandaloneBinary(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            luaPlatform,
            starter,
            mainModule,
            components);
      case INPLACE:
        return getInPlaceBinary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            luaPlatform.getCxxPlatform(),
            starter,
            components);
    }
    throw new IllegalStateException(
        String.format("%s: unexpected package style %s", buildTarget, packageStyle));
  }

  // Return the C/C++ platform to build against.
  private LuaPlatform getPlatform(BuildTarget target, AbstractLuaBinaryDescriptionArg arg) {
    LuaPlatformsProvider luaPlatformsProvider =
        toolchainProvider.getByName(LuaPlatformsProvider.DEFAULT_NAME, LuaPlatformsProvider.class);

    FlavorDomain<LuaPlatform> luaPlatforms = luaPlatformsProvider.getLuaPlatforms();

    Optional<LuaPlatform> flavorPlatform = luaPlatforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getPlatform().isPresent()) {
      return luaPlatforms.getValue(arg.getPlatform().get());
    }

    return luaPlatformsProvider.getDefaultLuaPlatform();
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      LuaBinaryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    LuaPlatform luaPlatform = getPlatform(buildTarget, args);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    FlavorDomain<PythonPlatform> pythonPlatforms =
        toolchainProvider
            .getByName(PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.class)
            .getPythonPlatforms();
    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(buildTarget)
            .orElse(
                pythonPlatforms.getValue(
                    args.getPythonPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    LuaBinaryPackageComponents components =
        getPackageComponentsFromDeps(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            luaPlatform,
            pythonPlatform,
            args.getNativeStarterLibrary()
                .map(Optional::of)
                .orElse(luaPlatform.getNativeStarterLibrary()),
            args.getMainModule(),
            args.getPackageStyle().orElse(luaPlatform.getPackageStyle()),
            graphBuilder.getAllRules(
                LuaUtil.getDeps(
                    luaPlatform.getCxxPlatform(), args.getDeps(), args.getPlatformDeps())),
            context.getCellPathResolver());
    LuaPlatform.PackageStyle packageStyle =
        args.getPackageStyle().orElse(luaPlatform.getPackageStyle());
    Tool binary =
        getBinary(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            luaPlatform,
            args.getMainModule(),
            components.getStarter(),
            components.getComponents(),
            packageStyle);
    return new LuaBinary(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(BuildableSupport.getDepsCollection(binary, graphBuilder)),
        getOutputPath(buildTarget, projectFilesystem, luaPlatform),
        binary,
        args.getMainModule(),
        components.getComponents(),
        luaPlatform.getLua().resolve(graphBuilder, buildTarget.getTargetConfiguration()),
        packageStyle);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractLuaBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    LuaPlatform luaPlatform = getPlatform(buildTarget, constructorArg);
    if (luaPlatform.getPackageStyle() == LuaPlatform.PackageStyle.STANDALONE) {
      extraDepsBuilder.addAll(
          luaPlatform.getPackager().getParseTimeDeps(buildTarget.getTargetConfiguration()));
    }
    extraDepsBuilder.addAll(getNativeStarterDepTargets(luaPlatform));
  }

  public enum StarterType {
    PURE,
    NATIVE,
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractLuaBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    String getMainModule();

    Optional<BuildTarget> getNativeStarterLibrary();

    Optional<String> getPythonPlatform();

    Optional<Flavor> getPlatform();

    Optional<LuaPlatform.PackageStyle> getPackageStyle();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }
  }
}
