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

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.attr.ImplicitFlavorsInferringDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class CxxBinaryDescription
    implements DescriptionWithTargetGraph<CxxBinaryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<CxxBinaryDescription.AbstractCxxBinaryDescriptionArg>,
        ImplicitFlavorsInferringDescription,
        MetadataProvidingDescription<CxxBinaryDescriptionArg>,
        VersionRoot<CxxBinaryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors;
  private final CxxBinaryFactory cxxBinaryFactory;
  private final CxxBinaryMetadataFactory cxxBinaryMetadataFactory;
  private final CxxBinaryFlavored cxxBinaryFlavored;

  public CxxBinaryDescription(
      ToolchainProvider toolchainProvider,
      CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors,
      CxxBinaryFactory cxxBinaryFactory,
      CxxBinaryMetadataFactory cxxBinaryMetadataFactory,
      CxxBinaryFlavored cxxBinaryFlavored) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBinaryImplicitFlavors = cxxBinaryImplicitFlavors;
    this.cxxBinaryFactory = cxxBinaryFactory;
    this.cxxBinaryMetadataFactory = cxxBinaryMetadataFactory;
    this.cxxBinaryFlavored = cxxBinaryFlavored;
  }

  @Override
  public Class<CxxBinaryDescriptionArg> getConstructorArgType() {
    return CxxBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxBinaryDescriptionArg args) {
    return cxxBinaryFactory.createBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        context.getActionGraphBuilder(),
        context.getCellPathResolver(),
        args,
        ImmutableSortedSet.of());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractCxxBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(
        CxxPlatforms.findDepsForTargetFromConstructorArgs(
            getCxxPlatformsProvider(), buildTarget, constructorArg.getDefaultPlatform()));
    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(targetGraphOnlyDepsBuilder::add));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return cxxBinaryFlavored.flavorDomains();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> inputFlavors) {
    return cxxBinaryFlavored.hasFlavors(inputFlavors);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBinaryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return cxxBinaryMetadataFactory.createMetadata(
        buildTarget, graphBuilder, args.getDeps(), metadataClass);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    return cxxBinaryImplicitFlavors.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors, DescriptionCache.getRuleType(this));
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  public interface CommonArg extends LinkableCxxConstructorArg, HasVersionUniverse, HasDepsQuery {
    @Value.Default
    default boolean getLinkDepsQueryWhole() {
      return false;
    }
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractCxxBinaryDescriptionArg extends CxxBinaryDescription.CommonArg {}
}
