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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxInferEnhancer;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;

public class MultiarchFileInfos {

  // Utility class, do not instantiate.
  private MultiarchFileInfos() {}

  /**
   * Inspect the given build target and return information about it if its a fat binary.
   *
   * @return non-empty when the target represents a fat binary.
   * @throws HumanReadableException when the target is a fat binary but has incompatible flavors.
   */
  public static Optional<MultiarchFileInfo> create(
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms, BuildTarget target) {
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinFlavors(appleCxxPlatforms.getFlavors(), target.getFlavors());
    if (thinFlavorSets.size() <= 1) { // Actually a thin binary
      return Optional.empty();
    }

    assertTargetSupportsMultiarch(target);

    AppleCxxPlatform representativePlatform = null;
    AppleSdk sdk = null;
    for (SortedSet<Flavor> flavorSet : thinFlavorSets) {
      AppleCxxPlatform platform =
          Objects.requireNonNull(appleCxxPlatforms.getValue(flavorSet).orElse(null));
      if (sdk == null) {
        sdk = platform.getAppleSdk();
        representativePlatform = platform;
      } else if (sdk != platform.getAppleSdk()) {
        throw new HumanReadableException(
            "%s: Fat binaries can only be generated from binaries compiled for the same SDK.",
            target);
      }
    }

    MultiarchFileInfo.Builder builder =
        MultiarchFileInfo.builder()
            .setFatTarget(target)
            .setRepresentativePlatform(Objects.requireNonNull(representativePlatform));

    BuildTarget platformFreeTarget = target.withoutFlavors(appleCxxPlatforms.getFlavors());
    for (SortedSet<Flavor> flavorSet : thinFlavorSets) {
      builder.addThinTargets(platformFreeTarget.withFlavors(flavorSet));
    }

    return Optional.of(builder.build());
  }

  public static void checkTargetSupportsMultiarch(
      FlavorDomain<AppleCxxPlatform> appleCxxPlatforms, BuildTarget target) {
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinFlavors(appleCxxPlatforms.getFlavors(), target.getFlavors());
    if (thinFlavorSets.size() <= 1) { // Actually a thin binary
      return;
    }

    assertTargetSupportsMultiarch(target);
  }

  private static void assertTargetSupportsMultiarch(BuildTarget target) {
    if (!Sets.intersection(target.getFlavors(), FORBIDDEN_BUILD_ACTIONS).isEmpty()) {
      throw new HumanReadableException(
          "%s: Fat binaries is only supported when building an actual binary.", target);
    }
  }

  /**
   * Expand flavors representing a fat binary into its thin binary equivalents.
   *
   * <p>Useful when dealing with functions unaware of fat binaries.
   *
   * <p>This does not actually check that the particular flavor set is valid.
   */
  public static ImmutableList<ImmutableSortedSet<Flavor>> generateThinFlavors(
      Set<Flavor> platformFlavors, SortedSet<Flavor> flavors) {
    Set<Flavor> platformFreeFlavors = Sets.difference(flavors, platformFlavors);
    ImmutableList.Builder<ImmutableSortedSet<Flavor>> thinTargetsBuilder = ImmutableList.builder();
    for (Flavor flavor : flavors) {
      if (platformFlavors.contains(flavor)) {
        thinTargetsBuilder.add(
            ImmutableSortedSet.<Flavor>naturalOrder()
                .addAll(platformFreeFlavors)
                .add(flavor)
                .build());
      }
    }
    return thinTargetsBuilder.build();
  }

  /**
   * Generate a fat rule from thin rules.
   *
   * <p>Invariant: thinRules contain all the thin rules listed in info.getThinTargets().
   */
  public static BuildRule requireMultiarchRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      MultiarchFileInfo info,
      ImmutableSortedSet<BuildRule> thinRules,
      CxxBuckConfig cxxBuckConfig) {
    Optional<BuildRule> existingRule = graphBuilder.getRuleOptional(info.getFatTarget());
    if (existingRule.isPresent()) {
      return existingRule.get();
    }

    // Thin rules filtered to remove those with null output
    ImmutableSortedSet<SourcePath> inputs =
        FluentIterable.from(thinRules)
            .transform(BuildRule::getSourcePathToOutput)
            .filter(SourcePath.class)
            .toSortedSet(Ordering.natural());

    // If any thin rule exists with output, use `MultiarchFile` to generate binary. Otherwise,
    // use a `NoopBuildRule` to handle inputs like those without any sources.
    if (!inputs.isEmpty()) {
      String multiarchOutputPathFormat =
          getMultiarchOutputFormatString(graphBuilder.getSourcePathResolver(), inputs);

      MultiarchFile multiarchFile =
          new MultiarchFile(
              buildTarget,
              projectFilesystem,
              params.withoutDeclaredDeps().withExtraDeps(thinRules),
              graphBuilder,
              info.getRepresentativePlatform().getLipo(),
              inputs,
              cxxBuckConfig.shouldCacheLinks(),
              BuildTargetPaths.getGenPath(
                  projectFilesystem, buildTarget, multiarchOutputPathFormat));
      graphBuilder.addToIndex(multiarchFile);
      return multiarchFile;
    } else {
      return new NoopBuildRule(buildTarget, projectFilesystem);
    }
  }

  private static final String BASE_OUTPUT_FORMAT_STRING = "%s";
  private static final String NESTED_OUTPUT_FORMAT_STRING = "%s/";

  /**
   * Generate the format string for the fat rule output. If all the thin rules have the same output
   * file name, use this as the file name for the fat rule output. Otherwise, default to simple
   * string substitution.
   */
  @VisibleForTesting
  static String getMultiarchOutputFormatString(
      SourcePathResolver pathResolver, ImmutableSortedSet<SourcePath> inputs) {
    if (inputs.isEmpty()) {
      return BASE_OUTPUT_FORMAT_STRING;
    }

    String outputFileName = pathResolver.getAbsolutePath(inputs.first()).getFileName().toString();

    for (SourcePath input : inputs) {
      String inputFileName = pathResolver.getAbsolutePath(input).getFileName().toString();

      if (!outputFileName.equals(inputFileName)) {
        // not all input files have the same name, so don't try to match them
        return BASE_OUTPUT_FORMAT_STRING;
      }
    }

    // all input files have same output file name, match it for the output
    return NESTED_OUTPUT_FORMAT_STRING + outputFileName;
  }

  private static final ImmutableSet<Flavor> FORBIDDEN_BUILD_ACTIONS =
      ImmutableSet.<Flavor>builder()
          .addAll(CxxInferEnhancer.INFER_FLAVOR_DOMAIN.getFlavors())
          .add(CxxCompilationDatabase.COMPILATION_DATABASE)
          .build();
}
