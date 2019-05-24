/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class PythonLibraryDescription
    implements DescriptionWithTargetGraph<PythonLibraryDescriptionArg>,
        VersionPropagator<PythonLibraryDescriptionArg>,
        MetadataProvidingDescription<PythonLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  private static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("Python Metadata Type", MetadataType.class);

  public PythonLibraryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<PythonLibraryDescriptionArg> getConstructorArgType() {
    return PythonLibraryDescriptionArg.class;
  }

  @Override
  public PythonLibrary createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonLibraryDescriptionArg args) {
    return new PythonLibrary(
        buildTarget, context.getProjectFilesystem(), params, args.isExcludeDepsFromMergedLinking());
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      PythonLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {

    Optional<Map.Entry<Flavor, MetadataType>> optionalType =
        METADATA_TYPE.getFlavorAndValue(buildTarget);
    if (!optionalType.isPresent()) {
      return Optional.empty();
    }

    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getUnresolvedCxxPlatforms();

    Map.Entry<Flavor, MetadataType> type = optionalType.get();

    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());
    switch (type.getValue()) {
      case PACKAGE_COMPONENTS:
        {
          Map.Entry<Flavor, PythonPlatform> pythonPlatform =
              getPythonPlatforms()
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, UnresolvedCxxPlatform> cxxPlatform =
              cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
          baseTarget = buildTarget.withoutFlavors(pythonPlatform.getKey(), cxxPlatform.getKey());

          Path baseModule = PythonUtil.getBasePath(baseTarget, args.getBaseModule());
          PythonPackageComponents components =
              PythonPackageComponents.of(
                  PythonUtil.getModules(
                      baseTarget,
                      graphBuilder,
                      pythonPlatform.getValue(),
                      cxxPlatform
                          .getValue()
                          .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                      "srcs",
                      baseModule,
                      args.getSrcs(),
                      args.getPlatformSrcs(),
                      args.getVersionedSrcs(),
                      selectedVersions),
                  PythonUtil.getModules(
                      baseTarget,
                      graphBuilder,
                      pythonPlatform.getValue(),
                      cxxPlatform
                          .getValue()
                          .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                      "resources",
                      baseModule,
                      args.getResources(),
                      args.getPlatformResources(),
                      args.getVersionedResources(),
                      selectedVersions),
                  ImmutableMap.of(),
                  ImmutableMultimap.of(),
                  args.getZipSafe());

          return Optional.of(components).map(metadataClass::cast);
        }

      case PACKAGE_DEPS:
        {
          Map.Entry<Flavor, PythonPlatform> pythonPlatform =
              getPythonPlatforms()
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, UnresolvedCxxPlatform> cxxPlatform =
              cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
          ImmutableList<BuildTarget> depTargets =
              PythonUtil.getDeps(
                  pythonPlatform.getValue(),
                  cxxPlatform
                      .getValue()
                      .resolve(graphBuilder, buildTarget.getTargetConfiguration()),
                  args.getDeps(),
                  args.getPlatformDeps());
          return Optional.of(graphBuilder.getAllRules(depTargets)).map(metadataClass::cast);
        }
    }

    throw new IllegalStateException();
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private FlavorDomain<PythonPlatform> getPythonPlatforms() {
    return toolchainProvider
        .getByName(PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.class)
        .getPythonPlatforms();
  }

  enum MetadataType implements FlavorConvertible {
    PACKAGE_COMPONENTS(InternalFlavor.of("package-components")),
    PACKAGE_DEPS(InternalFlavor.of("package-deps")),
    ;

    private final Flavor flavor;

    MetadataType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  interface CoreArg extends CommonDescriptionArg, HasDeclaredDeps, HasTests {
    @Value.Default
    default SourceSortedSet getSrcs() {
      return SourceSortedSet.EMPTY;
    }

    Optional<VersionMatchedCollection<SourceSortedSet>> getVersionedSrcs();

    @Value.Default
    default PatternMatchedCollection<SourceSortedSet> getPlatformSrcs() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default SourceSortedSet getResources() {
      return SourceSortedSet.EMPTY;
    }

    Optional<VersionMatchedCollection<SourceSortedSet>> getVersionedResources();

    @Value.Default
    default PatternMatchedCollection<SourceSortedSet> getPlatformResources() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getBaseModule();

    Optional<Boolean> getZipSafe();

    @Value.Default
    default boolean isExcludeDepsFromMergedLinking() {
      return false;
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPythonLibraryDescriptionArg extends CoreArg {}
}
