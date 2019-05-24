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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TopologicalSort;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.LinkOutputPostprocessor;
import com.facebook.buck.cxx.PrebuiltCxxLibrary;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.elf.Elf;
import com.facebook.buck.cxx.toolchain.elf.ElfSection;
import com.facebook.buck.cxx.toolchain.elf.ElfSymbolTable;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.immutables.value.Value;

/**
 * Helper for AndroidLibraryGraphEnhancer to handle semi-transparent merging of native libraries.
 *
 * <p>Older versions of Android have a limit on how many DSOs they can load into one process. To
 * work around this limit, it can be helpful to merge multiple libraries together based on a per-app
 * configuration. This enhancer replaces the raw NativeLinkable rules with versions that merge
 * multiple logical libraries into one physical library. We also generate code to allow the merge
 * results to be queried at runtime.
 *
 * <p>Note that when building an app that uses merged libraries, we need to adjust the way we link
 * *all* libraries, because their DT_NEEDED can change even if they aren't being merged themselves.
 * Future work could identify cases where the original build rules are sufficient.
 */
class NativeLibraryMergeEnhancer {
  private NativeLibraryMergeEnhancer() {}

  @SuppressWarnings("PMD.PrematureDeclaration")
  static NativeLibraryMergeEnhancementResult enhance(
      CellPathResolver cellPathResolver,
      CxxBuckConfig cxxBuckConfig,
      ActionGraphBuilder graphBuilder,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      Map<String, List<Pattern>> mergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      ImmutableMultimap<APKModule, NativeLinkable> linkables,
      ImmutableMultimap<APKModule, NativeLinkable> linkablesAssets) {

    NativeLibraryMergeEnhancementResult.Builder builder =
        NativeLibraryMergeEnhancementResult.builder();

    ImmutableSet<APKModule> modules =
        ImmutableSet.<APKModule>builder()
            .addAll(linkables.keySet())
            .addAll(linkablesAssets.keySet())
            .build();

    Stream<? extends NativeLinkable> allModulesLinkables = Stream.empty();
    ImmutableSet.Builder<NativeLinkable> linkableAssetSetBuilder = ImmutableSet.builder();
    for (APKModule module : modules) {
      allModulesLinkables = Stream.concat(allModulesLinkables, linkables.get(module).stream());
      allModulesLinkables =
          Stream.concat(allModulesLinkables, linkablesAssets.get(module).stream());
      linkableAssetSetBuilder.addAll(linkablesAssets.get(module));
    }

    // Sort by build target here to ensure consistent behavior.
    Iterable<NativeLinkable> allLinkables =
        allModulesLinkables
            .sorted(Comparator.comparing(NativeLinkable::getBuildTarget))
            .collect(ImmutableList.toImmutableList());

    ImmutableSet<NativeLinkable> linkableAssetSet = linkableAssetSetBuilder.build();
    Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership =
        makeConstituentMap(buildTarget, mergeMap, allLinkables, linkableAssetSet);

    ImmutableSortedMap.Builder<String, String> sonameMapBuilder = ImmutableSortedMap.naturalOrder();
    ImmutableSetMultimap.Builder<String, String> sonameTargetsBuilder =
        ImmutableSetMultimap.builder();
    makeSonameMap(
        // sonames can *theoretically* differ per-platform, but right now they don't on Android,
        // so just pick the first platform and use that to get all the sonames.
        nativePlatforms.values().iterator().next().getCxxPlatform(),
        linkableMembership,
        sonameMapBuilder,
        sonameTargetsBuilder,
        graphBuilder);
    builder.setSonameMapping(sonameMapBuilder.build());
    ImmutableSortedMap.Builder<String, ImmutableSortedSet<String>> finalSonameTargetsBuilder =
        ImmutableSortedMap.naturalOrder();
    sonameTargetsBuilder
        .build()
        .asMap()
        .forEach((k, v) -> finalSonameTargetsBuilder.put(k, ImmutableSortedSet.copyOf(v)));
    builder.setSharedObjectTargets(finalSonameTargetsBuilder.build());

    Iterable<MergedNativeLibraryConstituents> orderedConstituents =
        getOrderedMergedConstituents(buildTarget, graphBuilder, linkableMembership);

    Optional<NativeLinkable> glueLinkable = Optional.empty();
    if (nativeLibraryMergeGlue.isPresent()) {
      BuildRule rule = graphBuilder.getRule(nativeLibraryMergeGlue.get());
      if (!(rule instanceof NativeLinkable)) {
        throw new RuntimeException(
            "Native library merge glue "
                + rule.getBuildTarget()
                + " for application "
                + buildTarget
                + " is not linkable.");
      }
      glueLinkable = Optional.of(((NativeLinkable) rule));
    }

    Set<MergedLibNativeLinkable> mergedLinkables =
        createLinkables(
            cellPathResolver,
            cxxBuckConfig,
            graphBuilder,
            buildTarget,
            projectFilesystem,
            glueLinkable,
            nativeLibraryMergeLocalizedSymbols.map(ImmutableSortedSet::copyOf),
            orderedConstituents);

    ImmutableMap.Builder<NativeLinkable, APKModule> linkableToModuleMapBuilder =
        ImmutableMap.builder();
    for (Map.Entry<APKModule, NativeLinkable> entry : linkables.entries()) {
      linkableToModuleMapBuilder.put(entry.getValue(), entry.getKey());
    }
    for (Map.Entry<APKModule, NativeLinkable> entry : linkablesAssets.entries()) {
      linkableToModuleMapBuilder.put(entry.getValue(), entry.getKey());
    }
    ImmutableMap<NativeLinkable, APKModule> linkableToModuleMap =
        linkableToModuleMapBuilder.build();

    for (MergedLibNativeLinkable linkable : mergedLinkables) {
      APKModule module = getModuleForLinkable(linkable, linkableToModuleMap);
      if (Collections.disjoint(linkable.constituents.getLinkables(), linkableAssetSet)) {
        builder.putMergedLinkables(module, linkable);
      } else if (linkableAssetSet.containsAll(linkable.constituents.getLinkables())) {
        builder.putMergedLinkablesAssets(module, linkable);
      }
    }

    return builder.build();
  }

  private static APKModule getModuleForLinkable(
      MergedLibNativeLinkable linkable,
      ImmutableMap<NativeLinkable, APKModule> linkableToModuleMap) {
    APKModule module = null;
    for (NativeLinkable constituent : linkable.constituents.getLinkables()) {
      APKModule constituentModule = linkableToModuleMap.get(constituent);
      if (module == null) {
        module = constituentModule;
      }
      if (module != constituentModule) {
        StringBuilder sb = new StringBuilder();
        sb.append("Native library merge of ")
            .append(linkable)
            .append(" has inconsistent application module mappings: ");
        for (NativeLinkable innerConstituent : linkable.constituents.getLinkables()) {
          APKModule innerConstituentModule = linkableToModuleMap.get(innerConstituent);
          sb.append(innerConstituent).append(" -> ").append(innerConstituentModule).append(", ");
        }
        throw new RuntimeException(
            "Native library merge of "
                + linkable
                + " has inconsistent application module mappings: "
                + sb);
      }
    }
    return Objects.requireNonNull(module);
  }

  private static Map<NativeLinkable, MergedNativeLibraryConstituents> makeConstituentMap(
      BuildTarget buildTarget,
      Map<String, List<Pattern>> mergeMap,
      Iterable<NativeLinkable> allLinkables,
      ImmutableSet<NativeLinkable> linkableAssetSet) {
    List<MergedNativeLibraryConstituents> allConstituents = new ArrayList<>();

    for (Map.Entry<String, List<Pattern>> mergeConfigEntry : mergeMap.entrySet()) {
      String mergeSoname = mergeConfigEntry.getKey();
      List<Pattern> patterns = mergeConfigEntry.getValue();

      MergedNativeLibraryConstituents.Builder constituentsBuilder =
          MergedNativeLibraryConstituents.builder().setSoname(mergeSoname);

      for (Pattern pattern : patterns) {
        for (NativeLinkable linkable : allLinkables) {
          // TODO(dreiss): Might be a good idea to cache .getBuildTarget().toString().
          if (pattern.matcher(linkable.getBuildTarget().toString()).find()) {
            constituentsBuilder.addLinkables(linkable);
          }
        }
      }

      allConstituents.add(constituentsBuilder.build());
    }

    Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership = new HashMap<>();
    for (MergedNativeLibraryConstituents constituents : allConstituents) {
      boolean hasNonAssets = false;
      boolean hasAssets = false;

      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkableMembership.containsKey(linkable)) {
          throw new HumanReadableException(
              String.format(
                  "Error: When processing %s, attempted to merge %s into both %s and %s",
                  buildTarget, linkable, linkableMembership.get(linkable), constituents));
        }
        linkableMembership.put(linkable, constituents);

        if (linkableAssetSet.contains(linkable)) {
          hasAssets = true;
        } else {
          hasNonAssets = true;
        }
      }
      if (hasAssets && hasNonAssets) {
        StringBuilder sb = new StringBuilder();
        sb.append(
            String.format(
                "Error: When processing %s, merged lib '%s' contains both asset and non-asset libraries.\n",
                buildTarget, constituents));
        for (NativeLinkable linkable : constituents.getLinkables()) {
          sb.append(
              String.format(
                  "  %s -> %s\n",
                  linkable, linkableAssetSet.contains(linkable) ? "asset" : "not asset"));
        }
        throw new HumanReadableException(sb.toString());
      }
    }

    for (NativeLinkable linkable : allLinkables) {
      if (!linkableMembership.containsKey(linkable)) {
        linkableMembership.put(
            linkable, MergedNativeLibraryConstituents.builder().addLinkables(linkable).build());
      }
    }
    return linkableMembership;
  }

  private static void makeSonameMap(
      CxxPlatform anyAndroidCxxPlatform,
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership,
      ImmutableSortedMap.Builder<String, String> sonameMapBuilder,
      ImmutableSetMultimap.Builder<String, String> sonameTargetsBuilder,
      ActionGraphBuilder graphBuilder) {
    for (Map.Entry<NativeLinkable, MergedNativeLibraryConstituents> entry :
        linkableMembership.entrySet()) {
      Optional<String> mergedName = entry.getValue().getSoname();
      for (Map.Entry<String, SourcePath> sonameEntry :
          entry.getKey().getSharedLibraries(anyAndroidCxxPlatform, graphBuilder).entrySet()) {
        String origName = sonameEntry.getKey();
        SourcePath sourcePath = sonameEntry.getValue();
        boolean isActuallyMerged = entry.getValue().isActuallyMerged();
        if (isActuallyMerged) {
          sonameMapBuilder.put(origName, mergedName.get());
        }
        if (sourcePath instanceof BuildTargetSourcePath) {
          String actualName = isActuallyMerged ? mergedName.get() : origName;
          sonameTargetsBuilder.put(
              actualName,
              ((BuildTargetSourcePath) sourcePath)
                  .getTarget()
                  .getUnflavoredBuildTarget()
                  .toString());
        }
      }
    }
  }

  /** Topo-sort the constituents objects so we can process deps first. */
  private static Iterable<MergedNativeLibraryConstituents> getOrderedMergedConstituents(
      BuildTarget buildTarget,
      BuildRuleResolver ruleResolver,
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership) {
    MutableDirectedGraph<MergedNativeLibraryConstituents> graph = new MutableDirectedGraph<>();
    for (MergedNativeLibraryConstituents constituents : linkableMembership.values()) {
      graph.addNode(constituents);
      for (NativeLinkable constituentLinkable : constituents.getLinkables()) {
        // For each dep of each constituent of each merged lib...
        for (NativeLinkable dep :
            Iterables.concat(
                constituentLinkable.getNativeLinkableDeps(ruleResolver),
                constituentLinkable.getNativeLinkableExportedDeps(ruleResolver))) {
          // If that dep is in a different merged lib, add a dependency.
          MergedNativeLibraryConstituents mergedDep =
              Objects.requireNonNull(linkableMembership.get(dep));
          if (mergedDep != constituents) {
            graph.addEdge(constituents, mergedDep);
          }
        }
      }
    }

    // Check for cycles in the merged dependency graph.
    // If any are found, spent a lot of effort building an error message
    // that actually shows the dependency cycle.
    for (ImmutableSet<MergedNativeLibraryConstituents> fullCycle : graph.findCycles()) {
      HashSet<MergedNativeLibraryConstituents> partialCycle = new LinkedHashSet<>();
      MergedNativeLibraryConstituents item = fullCycle.iterator().next();
      while (!partialCycle.contains(item)) {
        partialCycle.add(item);
        item =
            Sets.intersection(ImmutableSet.copyOf(graph.getOutgoingNodesFor(item)), fullCycle)
                .iterator()
                .next();
      }

      StringBuilder cycleString = new StringBuilder().append("[ ");
      StringBuilder depString = new StringBuilder();
      boolean foundStart = false;
      MergedNativeLibraryConstituents prevMember = null;
      for (MergedNativeLibraryConstituents member : partialCycle) {
        if (member == item) {
          foundStart = true;
        }
        if (foundStart) {
          cycleString.append(member);
          cycleString.append(" -> ");
        }
        if (prevMember != null) {
          Set<Pair<String, String>> depEdges =
              getRuleDependencies(ruleResolver, linkableMembership, prevMember, member);
          depString.append(formatRuleDependencies(depEdges, prevMember, member));
        }
        prevMember = member;
      }
      cycleString.append(item);
      cycleString.append(" ]");

      Set<Pair<String, String>> depEdges =
          getRuleDependencies(
              ruleResolver, linkableMembership, Objects.requireNonNull(prevMember), item);
      depString.append(formatRuleDependencies(depEdges, Objects.requireNonNull(prevMember), item));

      throw new HumanReadableException(
          "Error: Dependency cycle detected when merging native libs for "
              + buildTarget
              + ": "
              + cycleString
              + "\n"
              + depString);
    }

    return TopologicalSort.sort(graph);
  }

  /**
   * Calculates the actual target dependency edges between two merged libraries. Returns them as
   * strings for printing.
   */
  private static Set<Pair<String, String>> getRuleDependencies(
      BuildRuleResolver ruleResolver,
      Map<NativeLinkable, MergedNativeLibraryConstituents> linkableMembership,
      MergedNativeLibraryConstituents from,
      MergedNativeLibraryConstituents to) {

    // We do this work again because we want to avoid storing extraneous information on the
    // normal path. We know we're iterating over a cycle, so we can afford to do some work to
    // figure out the actual targets causing it.
    Set<Pair<String, String>> buildTargets = new LinkedHashSet<>();
    for (NativeLinkable sourceLinkable : from.getLinkables()) {
      for (NativeLinkable targetLinkable :
          Iterables.concat(
              sourceLinkable.getNativeLinkableDeps(ruleResolver),
              sourceLinkable.getNativeLinkableExportedDeps(ruleResolver))) {
        if (linkableMembership.get(targetLinkable) == to) {
          // Normalize to string names for printing.
          buildTargets.add(
              new Pair<>(
                  sourceLinkable.getBuildTarget().toString(),
                  targetLinkable.getBuildTarget().toString()));
        }
      }
    }
    return buildTargets;
  }

  private static String formatRuleDependencies(
      Set<Pair<String, String>> edges,
      MergedNativeLibraryConstituents from,
      MergedNativeLibraryConstituents to) {
    StringBuilder depString = new StringBuilder();
    depString.append("Dependencies between ").append(from).append(" and ").append(to).append(":\n");
    for (Pair<String, String> ruleEdge : edges) {
      depString
          .append("  ")
          .append(ruleEdge.getFirst())
          .append(" -> ")
          .append(ruleEdge.getSecond())
          .append("\n");
    }
    return depString.toString();
  }

  /** Create the final Linkables that will be passed to the later stages of graph enhancement. */
  private static Set<MergedLibNativeLinkable> createLinkables(
      CellPathResolver cellPathResolver,
      CxxBuckConfig cxxBuckConfig,
      ActionGraphBuilder graphBuilder,
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      Optional<NativeLinkable> glueLinkable,
      Optional<ImmutableSortedSet<String>> symbolsToLocalize,
      Iterable<MergedNativeLibraryConstituents> orderedConstituents) {
    // Map from original linkables to the Linkables they have been merged into.
    Map<NativeLinkable, MergedLibNativeLinkable> mergeResults = new HashMap<>();

    for (MergedNativeLibraryConstituents constituents : orderedConstituents) {
      ImmutableCollection<NativeLinkable> preMergeLibs = constituents.getLinkables();

      List<MergedLibNativeLinkable> orderedDeps =
          getStructuralDeps(constituents, x -> x.getNativeLinkableDeps(graphBuilder), mergeResults);
      List<MergedLibNativeLinkable> orderedExportedDeps =
          getStructuralDeps(
              constituents, x -> x.getNativeLinkableExportedDeps(graphBuilder), mergeResults);

      ProjectFilesystem targetProjectFilesystem = projectFilesystem;
      if (!constituents.isActuallyMerged()) {
        // There is only one target
        BuildTarget target = preMergeLibs.iterator().next().getBuildTarget();
        if (!target.getCellPath().equals(projectFilesystem.getRootPath())) {
          // Switch the target project filesystem
          targetProjectFilesystem = graphBuilder.getRule(target).getProjectFilesystem();
        }
      }

      MergedLibNativeLinkable mergedLinkable =
          new MergedLibNativeLinkable(
              cellPathResolver,
              cxxBuckConfig,
              graphBuilder,
              baseBuildTarget,
              targetProjectFilesystem,
              constituents,
              orderedDeps,
              orderedExportedDeps,
              glueLinkable,
              symbolsToLocalize);

      for (NativeLinkable lib : preMergeLibs) {
        // Track what was merged into this so later linkables can find us as a dependency.
        mergeResults.put(lib, mergedLinkable);
      }
    }

    return ImmutableSortedSet.copyOf(
        Comparator.comparing(NativeLinkable::getBuildTarget), mergeResults.values());
  }

  /**
   * Get the merged version of all deps, across all platforms.
   *
   * @param depType Function that returns the proper dep type: exported or not.
   */
  private static List<MergedLibNativeLinkable> getStructuralDeps(
      MergedNativeLibraryConstituents constituents,
      Function<NativeLinkable, Iterable<? extends NativeLinkable>> depType,
      Map<NativeLinkable, MergedLibNativeLinkable> alreadyMerged) {
    // Using IdentityHashMap as a hash set.
    Map<MergedLibNativeLinkable, Void> structuralDeps = new HashMap<>();
    for (NativeLinkable linkable : constituents.getLinkables()) {
      for (NativeLinkable dep : depType.apply(linkable)) {
        MergedLibNativeLinkable mappedDep = alreadyMerged.get(dep);
        if (mappedDep == null) {
          if (constituents.getLinkables().contains(dep)) {
            // We're depending on one of our other constituents.  We can drop this.
            continue;
          }
          throw new RuntimeException(
              "Can't find mapped dep of " + dep + " for " + linkable + ".  This is a bug.");
        }
        structuralDeps.put(mappedDep, null);
      }
    }
    // Sort here to ensure consistent ordering, because the build target depends on the order.
    return structuralDeps.keySet().stream()
        .sorted(Comparator.comparing(MergedLibNativeLinkable::getBuildTarget))
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Data object for internal use, representing the source libraries getting merged together into
   * one DSO. Libraries not being merged will have one linkable and no soname.
   */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractMergedNativeLibraryConstituents
      implements Comparable<AbstractMergedNativeLibraryConstituents> {
    public abstract Optional<String> getSoname();

    public abstract ImmutableSet<NativeLinkable> getLinkables();

    /** @return true if this is a library defined in the merge config. */
    public boolean isActuallyMerged() {
      return getSoname().isPresent();
    }

    @Value.Check
    protected void check() {
      if (!isActuallyMerged()) {
        Preconditions.checkArgument(
            getLinkables().size() == 1,
            "BUG: %s is not 'actually merged', but does not consist of a single linkable");
      }
    }

    @Override
    public String toString() {
      if (isActuallyMerged()) {
        return "merge:" + getSoname().get();
      }
      return "no-merge:" + getLinkables().iterator().next().getBuildTarget();
    }

    @Override
    public int compareTo(AbstractMergedNativeLibraryConstituents other) {
      return toString().compareTo(other.toString());
    }
  }

  @Value.Immutable(copy = true)
  @BuckStyleImmutable
  abstract static class AbstractNativeLibraryMergeEnhancementResult {
    public abstract ImmutableMultimap<APKModule, NativeLinkable> getMergedLinkables();

    public abstract ImmutableMultimap<APKModule, NativeLinkable> getMergedLinkablesAssets();

    public abstract ImmutableSortedMap<String, String> getSonameMapping();

    /** This is for human consumption only. */
    public abstract ImmutableSortedMap<String, ImmutableSortedSet<String>> getSharedObjectTargets();
  }

  /**
   * Our own implementation of NativeLinkable, which is consumed by later phases of graph
   * enhancement. It represents a single merged library.
   */
  private static class MergedLibNativeLinkable implements NativeLinkable {
    private final CxxBuckConfig cxxBuckConfig;
    private final ActionGraphBuilder graphBuilder;
    private final ProjectFilesystem projectFilesystem;
    private final MergedNativeLibraryConstituents constituents;
    private final Optional<NativeLinkable> glueLinkable;
    private final Optional<ImmutableSortedSet<String>> symbolsToLocalize;
    private final Map<NativeLinkable, MergedLibNativeLinkable> mergedDepMap;
    private final BuildTarget buildTarget;
    private final boolean canUseOriginal;
    private final CellPathResolver cellPathResolver;
    // Note: update constructBuildTarget whenever updating new fields.

    MergedLibNativeLinkable(
        CellPathResolver cellPathResolver,
        CxxBuckConfig cxxBuckConfig,
        ActionGraphBuilder graphBuilder,
        BuildTarget baseBuildTarget,
        ProjectFilesystem projectFilesystem,
        MergedNativeLibraryConstituents constituents,
        List<MergedLibNativeLinkable> orderedDeps,
        List<MergedLibNativeLinkable> orderedExportedDeps,
        Optional<NativeLinkable> glueLinkable,
        Optional<ImmutableSortedSet<String>> symbolsToLocalize) {
      this.cellPathResolver = cellPathResolver;
      this.cxxBuckConfig = cxxBuckConfig;
      this.graphBuilder = graphBuilder;
      this.projectFilesystem = projectFilesystem;
      this.constituents = constituents;
      this.glueLinkable = glueLinkable;
      this.symbolsToLocalize = symbolsToLocalize;

      Iterable<MergedLibNativeLinkable> allDeps =
          Iterables.concat(orderedDeps, orderedExportedDeps);
      Map<NativeLinkable, MergedLibNativeLinkable> mergedDeps = new HashMap<>();
      for (MergedLibNativeLinkable dep : allDeps) {
        for (NativeLinkable linkable : dep.constituents.getLinkables()) {
          MergedLibNativeLinkable old = mergedDeps.put(linkable, dep);
          if (old != null && old != dep) {
            throw new RuntimeException(
                String.format(
                    "BUG: When processing %s, dep %s mapped to both %s and %s",
                    constituents, linkable, dep, old));
          }
        }
      }
      mergedDepMap = Collections.unmodifiableMap(mergedDeps);

      canUseOriginal = computeCanUseOriginal(constituents, allDeps);

      buildTarget =
          constructBuildTarget(
              baseBuildTarget,
              constituents,
              orderedDeps,
              orderedExportedDeps,
              glueLinkable,
              symbolsToLocalize);
    }

    /**
     * If a library is not involved in merging, and neither are any of its transitive deps, we can
     * use just the original shared object, which lets us share cache with apps that don't use
     * merged libraries at all.
     */
    private static boolean computeCanUseOriginal(
        MergedNativeLibraryConstituents constituents, Iterable<MergedLibNativeLinkable> allDeps) {
      if (constituents.isActuallyMerged()) {
        return false;
      }

      for (MergedLibNativeLinkable dep : allDeps) {
        if (!dep.canUseOriginal) {
          return false;
        }
      }
      return true;
    }

    @Override
    public String toString() {
      return "MergedLibNativeLinkable<" + buildTarget + ">";
    }

    // TODO(dreiss): Maybe cache this and other methods?  Would have to be per-platform.
    String getSoname(CxxPlatform platform) {
      if (constituents.isActuallyMerged()) {
        return constituents.getSoname().get();
      }
      ImmutableMap<String, SourcePath> shared =
          constituents.getLinkables().iterator().next().getSharedLibraries(platform, graphBuilder);
      Preconditions.checkState(shared.size() == 1);
      return shared.keySet().iterator().next();
    }

    @Override
    public BuildTarget getBuildTarget() {
      return buildTarget;
    }

    private static BuildTarget constructBuildTarget(
        BuildTarget baseBuildTarget,
        MergedNativeLibraryConstituents constituents,
        List<MergedLibNativeLinkable> orderedDeps,
        List<MergedLibNativeLinkable> orderedExportedDeps,
        Optional<NativeLinkable> glueLinkable,
        Optional<ImmutableSortedSet<String>> symbolsToLocalize) {
      BuildTarget initialTarget;
      if (!constituents.isActuallyMerged()) {
        // This library isn't really merged.
        // We use its constituent as the base target to ensure that
        // it is shared between all apps with the same merge structure.
        initialTarget = constituents.getLinkables().iterator().next().getBuildTarget();
      } else {
        // If we're merging, construct a base target in the app's directory.
        // This ensure that all apps in this directory will
        // have a chance to share the target.
        initialTarget =
            baseBuildTarget
                .withoutFlavors()
                .withShortName(
                    "merged_lib_"
                        + Flavor.replaceInvalidCharacters(constituents.getSoname().get()));
      }

      // Two merged libs (for different apps) can have the same constituents,
      // but they still need to be separate rules if their dependencies differ.
      // However, we want to share if possible to share cache artifacts.
      // Therefore, transitively hash the dependencies' targets
      // to create a unique string to add to our target.
      Hasher hasher = Hashing.murmur3_32().newHasher();
      for (NativeLinkable nativeLinkable : constituents.getLinkables()) {
        hasher.putString(nativeLinkable.getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }
      // Hash all the merged deps, in order.
      hasher.putString("__DEPS__^", Charsets.UTF_8);
      for (MergedLibNativeLinkable dep : orderedDeps) {
        hasher.putString(dep.getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }
      // Separate exported deps.  This doesn't affect linking, but it can affect our dependents
      // if we're building two apps at once.
      hasher.putString("__EXPORT__^", Charsets.UTF_8);
      for (MergedLibNativeLinkable dep : orderedExportedDeps) {
        hasher.putString(dep.getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }

      // Glue can vary per-app, so include that in the hash as well.
      if (glueLinkable.isPresent()) {
        hasher.putString("__GLUE__^", Charsets.UTF_8);
        hasher.putString(glueLinkable.get().getBuildTarget().toString(), Charsets.UTF_8);
        hasher.putChar('^');
      }

      // Symbols to localize can vary per-app, so include that in the hash as well.
      if (symbolsToLocalize.isPresent()) {
        hasher.putString("__LOCALIZE__^", Charsets.UTF_8);
        hasher.putString(Joiner.on(',').join(symbolsToLocalize.get()), Charsets.UTF_8);
        hasher.putChar('^');
      }

      String mergeFlavor = "merge_structure_" + hasher.hash();

      return initialTarget.withAppendedFlavors(InternalFlavor.of(mergeFlavor));
    }

    private BuildTarget getBuildTargetForPlatform(CxxPlatform cxxPlatform) {
      return getBuildTarget().withAppendedFlavors(cxxPlatform.getFlavor());
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableDeps(
        BuildRuleResolver ruleResolver) {
      return getMappedDeps(x -> x.getNativeLinkableDeps(ruleResolver));
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
        BuildRuleResolver ruleResolver) {
      return getMappedDeps(x -> x.getNativeLinkableExportedDeps(ruleResolver));
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableDepsForPlatform(
        CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
      return getMappedDeps(l -> l.getNativeLinkableDepsForPlatform(cxxPlatform, ruleResolver));
    }

    @Override
    public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      return getMappedDeps(
          l -> l.getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder));
    }

    private Iterable<? extends NativeLinkable> getMappedDeps(
        Function<NativeLinkable, Iterable<? extends NativeLinkable>> depType) {
      ImmutableList.Builder<NativeLinkable> builder = ImmutableList.builder();

      for (NativeLinkable linkable : constituents.getLinkables()) {
        for (NativeLinkable dep : depType.apply(linkable)) {
          // Don't try to depend on ourselves.
          if (!constituents.getLinkables().contains(dep)) {
            builder.add(Objects.requireNonNull(mergedDepMap.get(dep)));
          }
        }
      }

      return builder.build();
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput(
        CxxPlatform cxxPlatform,
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {

      // This path gets taken for a force-static library.
      if (type == Linker.LinkableDepType.STATIC_PIC) {
        ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();
        for (NativeLinkable linkable : constituents.getLinkables()) {
          builder.add(
              linkable.getNativeLinkableInput(
                  cxxPlatform,
                  Linker.LinkableDepType.STATIC_PIC,
                  graphBuilder,
                  targetConfiguration));
        }
        return NativeLinkableInput.concat(builder.build());
      }

      // STATIC isn't valid because we always need PIC on Android.
      Preconditions.checkArgument(type == Linker.LinkableDepType.SHARED);

      ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
      // TODO(dreiss): Should we cache the output of getSharedLibraries per-platform?
      ImmutableMap<String, SourcePath> sharedLibraries =
          getSharedLibraries(cxxPlatform, graphBuilder);
      for (SourcePath sharedLib : sharedLibraries.values()) {
        // If we have a shared library, our dependents should link against it.
        // Might be multiple shared libraries if prebuilts are included.
        argsBuilder.add(SourcePathArg.of(sharedLib));
      }

      // If our constituents have exported linker flags, our dependents should use them.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkable instanceof CxxLibrary) {
          argsBuilder.addAll(
              ((CxxLibrary) linkable).getExportedLinkerFlags(cxxPlatform, graphBuilder));
        } else if (linkable instanceof PrebuiltCxxLibrary) {
          argsBuilder.addAll(
              ((PrebuiltCxxLibrary) linkable).getExportedLinkerArgs(cxxPlatform, graphBuilder));
        }
      }

      // If our constituents have post exported linker flags, our dependents should use them.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkable instanceof CxxLibrary) {
          argsBuilder.addAll(
              ((CxxLibrary) linkable).getExportedPostLinkerFlags(cxxPlatform, graphBuilder));
        } else if (linkable instanceof PrebuiltCxxLibrary) {
          argsBuilder.addAll(
              StringArg.from(
                  ((PrebuiltCxxLibrary) linkable).getExportedPostLinkerFlags(cxxPlatform)));
        }
      }

      return NativeLinkableInput.of(argsBuilder.build(), ImmutableList.of(), ImmutableList.of());
    }

    private NativeLinkableInput getImmediateNativeLinkableInput(
        CxxPlatform cxxPlatform,
        ActionGraphBuilder graphBuilder,
        TargetConfiguration targetConfiguration) {
      Linker linker = cxxPlatform.getLd().resolve(graphBuilder, targetConfiguration);
      ImmutableList.Builder<NativeLinkableInput> builder = ImmutableList.builder();
      ImmutableList<NativeLinkable> usingGlue = ImmutableList.of();
      if (glueLinkable.isPresent() && constituents.isActuallyMerged()) {
        usingGlue = ImmutableList.of(glueLinkable.get());
      }

      for (NativeLinkable linkable : Iterables.concat(usingGlue, constituents.getLinkables())) {
        if (linkable instanceof NativeLinkTarget) {
          // If this constituent is a NativeLinkTarget, use its input to get raw objects and
          // linker flags.
          builder.add(
              ((NativeLinkTarget) linkable)
                  .getNativeLinkTargetInput(
                      cxxPlatform, graphBuilder, graphBuilder.getSourcePathResolver()));
        } else {
          // Otherwise, just get the static pic output.
          NativeLinkableInput staticPic =
              linkable.getNativeLinkableInput(
                  cxxPlatform,
                  Linker.LinkableDepType.STATIC_PIC,
                  graphBuilder,
                  targetConfiguration);
          builder.add(
              staticPic.withArgs(
                  FluentIterable.from(staticPic.getArgs())
                      .transformAndConcat(
                          arg -> linker.linkWhole(arg, graphBuilder.getSourcePathResolver()))));
        }
      }
      return NativeLinkableInput.concat(builder.build());
    }

    @Override
    public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
      // If we have any non-static constituents, our preferred linkage is shared
      // (because stuff in Android is shared by default).  That's the common case.
      // If *all* of our constituents are force_static=True, we will also be preferred static.
      // Most commonly, that will happen when we're just wrapping a single force_static constituent.
      // It's also possible that multiple force_static libs could be merged,
      // but that has no effect.
      for (NativeLinkable linkable : constituents.getLinkables()) {
        if (linkable.getPreferredLinkage(cxxPlatform) != Linkage.STATIC) {
          return Linkage.SHARED;
        }
      }

      return Linkage.STATIC;
    }

    @Override
    public ImmutableMap<String, SourcePath> getSharedLibraries(
        CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
      if (getPreferredLinkage(cxxPlatform) == Linkage.STATIC) {
        return ImmutableMap.of();
      }

      ImmutableMap<String, SourcePath> originalSharedLibraries =
          constituents
              .getLinkables()
              .iterator()
              .next()
              .getSharedLibraries(cxxPlatform, graphBuilder);
      if (canUseOriginal
          || (!constituents.isActuallyMerged() && originalSharedLibraries.isEmpty())) {
        return originalSharedLibraries;
      }

      String soname = getSoname(cxxPlatform);
      BuildRule rule =
          graphBuilder.computeIfAbsent(
              getBuildTargetForPlatform(cxxPlatform),
              target ->
                  CxxLinkableEnhancer.createCxxLinkableBuildRule(
                      cxxBuckConfig,
                      cxxPlatform,
                      projectFilesystem,
                      graphBuilder,
                      target,
                      Linker.LinkType.SHARED,
                      Optional.of(soname),
                      BuildTargetPaths.getGenPath(
                          projectFilesystem, target, "%s/" + getSoname(cxxPlatform)),
                      ImmutableList.of(),
                      // Android Binaries will use share deps by default.
                      Linker.LinkableDepType.SHARED,
                      CxxLinkOptions.of(),
                      Iterables.concat(
                          getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
                          getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder)),
                      Optional.empty(),
                      Optional.empty(),
                      ImmutableSet.of(),
                      ImmutableSet.of(),
                      getImmediateNativeLinkableInput(
                          cxxPlatform, graphBuilder, target.getTargetConfiguration()),
                      constituents.isActuallyMerged()
                          ? symbolsToLocalize.map(SymbolLocalizingPostprocessor::new)
                          : Optional.empty(),
                      cellPathResolver));
      return ImmutableMap.of(soname, rule.getSourcePathToOutput());
    }
  }

  private static class SymbolLocalizingPostprocessor implements LinkOutputPostprocessor {
    @AddToRuleKey private final ImmutableSortedSet<String> symbolsToLocalize;

    @AddToRuleKey private final String postprocessorType = "localize-dynamic-symbols";

    SymbolLocalizingPostprocessor(ImmutableSortedSet<String> symbolsToLocalize) {
      this.symbolsToLocalize = symbolsToLocalize;
    }

    @Override
    public ImmutableList<Step> getSteps(BuildContext context, Path linkOutput, Path finalOutput) {
      return ImmutableList.of(
          new Step() {
            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              // Copy the output into place, then fix it in-place with mmap.
              Files.copy(linkOutput, finalOutput, StandardCopyOption.REPLACE_EXISTING);

              try (FileChannel channel =
                  FileChannel.open(
                      finalOutput, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                MappedByteBuffer buffer =
                    channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
                Elf elf = new Elf(buffer);
                fixSection(elf, ".dynsym", ".dynstr");
                fixSection(elf, ".symtab", ".strtab");
              }
              return StepExecutionResults.SUCCESS;
            }

            void fixSection(Elf elf, String sectionName, String stringSectionName)
                throws IOException {
              ElfSection section =
                  elf.getMandatorySectionByName(linkOutput, sectionName).getSection();
              ElfSection strings =
                  elf.getMandatorySectionByName(linkOutput, stringSectionName).getSection();
              ElfSymbolTable table = ElfSymbolTable.parse(elf.header.ei_class, section.body);

              ImmutableList.Builder<ElfSymbolTable.Entry> fixedEntries = ImmutableList.builder();
              RichStream.from(table.entries)
                  .map(
                      entry ->
                          new ElfSymbolTable.Entry(
                              entry.st_name,
                              fixInfoField(strings, entry.st_name, entry.st_info),
                              fixOtherField(strings, entry.st_name, entry.st_other),
                              entry.st_shndx,
                              entry.st_value,
                              entry.st_size))
                  .forEach(fixedEntries::add);
              ElfSymbolTable fixedUpTable = new ElfSymbolTable(fixedEntries.build());
              Preconditions.checkState(table.entries.size() == fixedUpTable.entries.size());
              section.body.rewind();
              fixedUpTable.write(elf.header.ei_class, section.body);
            }

            private ElfSymbolTable.Entry.Info fixInfoField(
                ElfSection strings, long st_name, ElfSymbolTable.Entry.Info st_info) {
              if (symbolsToLocalize.contains(strings.lookupString(st_name))) {
                // Change binding to local.
                return new ElfSymbolTable.Entry.Info(
                    ElfSymbolTable.Entry.Info.Bind.STB_LOCAL, st_info.st_type);
              }
              return st_info;
            }

            private int fixOtherField(ElfSection strings, long st_name, int st_other) {
              if (symbolsToLocalize.contains(strings.lookupString(st_name))) {
                // Change visibility to hidden.
                return (st_other & ~0x3) | 2;
              }
              return st_other;
            }

            @Override
            public String getShortName() {
              return "localize_dynamic_symbols";
            }

            @Override
            public String getDescription(ExecutionContext context) {
              return String.format(
                  "localize_dynamic_symbols --symbols %s --in %s --out %s",
                  symbolsToLocalize, linkOutput, finalOutput);
            }
          });
    }
  }
}
