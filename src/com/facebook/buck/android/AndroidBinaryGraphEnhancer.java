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

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidNativeLibsPackageableGraphEnhancer.AndroidNativeLibsGraphEnhancementResult;
import com.facebook.buck.android.FilterResourcesSteps.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.JavaLibraryDeps;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacLanguageLevelOptions;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.PrebuiltJar;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

public class AndroidBinaryGraphEnhancer {

  static final Flavor DEX_FLAVOR = InternalFlavor.of("dex");
  static final Flavor D8_FLAVOR = InternalFlavor.of("d8");
  private static final Flavor DEX_MERGE_FLAVOR = InternalFlavor.of("dex_merge");
  private static final Flavor TRIM_UBER_R_DOT_JAVA_FLAVOR =
      InternalFlavor.of("trim_uber_r_dot_java");
  private static final Flavor COMPILE_UBER_R_DOT_JAVA_FLAVOR =
      InternalFlavor.of("compile_uber_r_dot_java");
  private static final Flavor SPLIT_UBER_R_DOT_JAVA_JAR_FLAVOR =
      InternalFlavor.of("split_uber_r_dot_java_jar");
  private static final Flavor GENERATE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR =
      InternalFlavor.of("generate_native_lib_merge_map_generated_code");
  private static final Flavor COMPILE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR =
      InternalFlavor.of("compile_native_lib_merge_map_generated_code");
  static final Flavor NATIVE_LIBRARY_PROGUARD_FLAVOR =
      InternalFlavor.of("generate_proguard_config_from_native_libs");
  static final Flavor UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR =
      InternalFlavor.of("unstripped_native_libraries");
  static final Flavor PROGUARD_TEXT_OUTPUT_FLAVOR = InternalFlavor.of("proguard_text_output");
  static final Flavor NON_PREDEXED_DEX_BUILDABLE_FLAVOR =
      InternalFlavor.of("class_file_to_dex_processing");

  private final BuildTarget originalBuildTarget;
  private final SortedSet<BuildRule> originalDeps;
  private final ProjectFilesystem projectFilesystem;
  private final ToolchainProvider toolchainProvider;
  private final AndroidPlatformTarget androidPlatformTarget;
  private final BuildRuleParams buildRuleParams;
  private final boolean trimResourceIds;
  private final Optional<String> keepResourcePattern;
  private final boolean ignoreAaptProguardConfig;
  private final Optional<BuildTarget> nativeLibraryMergeCodeGenerator;
  private final ActionGraphBuilder graphBuilder;
  private final CellPathResolver cellPathResolver;
  private final PackageType packageType;
  private final boolean shouldPreDex;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final ImmutableCollection<SourcePath> nativeLibsToExclude;
  private final ImmutableCollection<NativeLinkable> nativeLinkablesToExclude;
  private final ImmutableCollection<SourcePath> nativeLibAssetsToExclude;
  private final ImmutableCollection<NativeLinkable> nativeLinkablesAssetsToExclude;
  private final JavaBuckConfig javaBuckConfig;
  private final Javac javac;
  private final JavacFactory javacFactory;
  private final JavacOptions javacOptions;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final BuildConfigFields buildConfigValues;
  private final Optional<SourcePath> buildConfigValuesFile;
  private final OptionalInt xzCompressionLevel;
  private final AndroidNativeLibsPackageableGraphEnhancer nativeLibsEnhancer;
  private final APKModuleGraph apkModuleGraph;
  private final Optional<BuildTarget> nativeLibraryProguardConfigGenerator;
  private final ListeningExecutorService dxExecutorService;
  private final DxConfig dxConfig;
  private final String dexTool;
  private final AndroidBinaryResourcesGraphEnhancer androidBinaryResourcesGraphEnhancer;
  private final NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs;
  private final ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex;

  AndroidBinaryGraphEnhancer(
      ToolchainProvider toolchainProvider,
      CellPathResolver cellPathResolver,
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams originalParams,
      ActionGraphBuilder graphBuilder,
      AaptMode aaptMode,
      ResourceCompressionMode resourceCompressionMode,
      ResourceFilter resourcesFilter,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<SourcePath> duplicateResourceWhitelistPath,
      Optional<String> resourceUnionPackage,
      ImmutableSet<String> locales,
      Optional<String> localizedStringFileName,
      Optional<SourcePath> manifest,
      Optional<SourcePath> manifestSkeleton,
      Optional<SourcePath> moduleManifestSkeleton,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters,
      boolean shouldBuildStringSourceMap,
      boolean shouldPreDex,
      DexSplitMode dexSplitMode,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude,
      ImmutableCollection<SourcePath> nativeLibsToExclude,
      ImmutableCollection<NativeLinkable> nativeLinkablesToExclude,
      ImmutableCollection<SourcePath> nativeLibAssetsToExclude,
      ImmutableCollection<NativeLinkable> nativeLinkableAssetsToExclude,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      boolean noAutoVersionResources,
      boolean noVersionTransitionsResources,
      boolean noAutoAddOverlayResources,
      JavaBuckConfig javaBuckConfig,
      JavacFactory javacFactory,
      JavacOptions javacOptions,
      EnumSet<ExopackageMode> exopackageModes,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      OptionalInt xzCompressionLevel,
      boolean trimResourceIds,
      Optional<String> keepResourcePattern,
      boolean ignoreAaptProguardConfig,
      Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap,
      Optional<BuildTarget> nativeLibraryMergeGlue,
      Optional<BuildTarget> nativeLibraryMergeCodeGenerator,
      Optional<ImmutableSortedSet<String>> nativeLibraryMergeLocalizedSymbols,
      Optional<BuildTarget> nativeLibraryProguardConfigGenerator,
      RelinkerMode relinkerMode,
      ImmutableList<Pattern> relinkerWhitelist,
      ListeningExecutorService dxExecutorService,
      ManifestEntries manifestEntries,
      CxxBuckConfig cxxBuckConfig,
      APKModuleGraph apkModuleGraph,
      DxConfig dxConfig,
      String dexTool,
      Optional<Arg> postFilterResourcesCmd,
      NonPredexedDexBuildableArgs nonPreDexedDexBuildableArgs,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      boolean useProtoFormat) {
    this.ignoreAaptProguardConfig = ignoreAaptProguardConfig;
    this.androidPlatformTarget = androidPlatformTarget;
    Preconditions.checkArgument(originalParams.getExtraDeps().get().isEmpty());
    this.projectFilesystem = projectFilesystem;
    this.toolchainProvider = toolchainProvider;
    this.buildRuleParams = originalParams;
    this.originalBuildTarget = originalBuildTarget;
    this.originalDeps = originalParams.getBuildDeps();
    this.graphBuilder = graphBuilder;
    this.cellPathResolver = cellPathResolver;
    this.packageType = packageType;
    this.shouldPreDex = shouldPreDex;
    this.dexSplitMode = dexSplitMode;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.resourcesToExclude = resourcesToExclude;
    this.nativeLibsToExclude = nativeLibsToExclude;
    this.nativeLinkablesToExclude = nativeLinkablesToExclude;
    this.nativeLibAssetsToExclude = nativeLibAssetsToExclude;
    this.nativeLinkablesAssetsToExclude = nativeLinkableAssetsToExclude;
    this.javaBuckConfig = javaBuckConfig;
    this.javacOptions = javacOptions;
    this.exopackageModes = exopackageModes;
    this.buildConfigValues = buildConfigValues;
    this.buildConfigValuesFile = buildConfigValuesFile;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.trimResourceIds = trimResourceIds;
    this.keepResourcePattern = keepResourcePattern;
    this.nativeLibraryMergeCodeGenerator = nativeLibraryMergeCodeGenerator;
    this.nativeLibraryProguardConfigGenerator = nativeLibraryProguardConfigGenerator;
    this.nativeLibsEnhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            toolchainProvider,
            cellPathResolver,
            graphBuilder,
            originalBuildTarget,
            projectFilesystem,
            cpuFilters,
            cxxBuckConfig,
            nativeLibraryMergeMap,
            nativeLibraryMergeGlue,
            nativeLibraryMergeLocalizedSymbols,
            relinkerMode,
            relinkerWhitelist,
            apkModuleGraph);
    this.androidBinaryResourcesGraphEnhancer =
        new AndroidBinaryResourcesGraphEnhancer(
            originalBuildTarget,
            projectFilesystem,
            toolchainProvider.getByName(
                AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
            graphBuilder,
            originalBuildTarget,
            ExopackageMode.enabledForResources(exopackageModes),
            manifest,
            manifestSkeleton,
            moduleManifestSkeleton,
            aaptMode,
            resourcesFilter,
            resourceCompressionMode,
            locales,
            localizedStringFileName,
            resourceUnionPackage,
            shouldBuildStringSourceMap,
            skipCrunchPngs,
            includesVectorDrawables,
            bannedDuplicateResourceTypes,
            duplicateResourceWhitelistPath,
            manifestEntries,
            postFilterResourcesCmd,
            noAutoVersionResources,
            noVersionTransitionsResources,
            noAutoAddOverlayResources,
            apkModuleGraph,
            useProtoFormat);
    this.apkModuleGraph = apkModuleGraph;
    this.dxConfig = dxConfig;
    this.nonPreDexedDexBuildableArgs = nonPreDexedDexBuildableArgs;
    this.rulesToExcludeFromDex = rulesToExcludeFromDex;
    this.dexTool = dexTool;
    this.javacFactory = javacFactory;
    this.javac = javacFactory.create(graphBuilder, null);
  }

  AndroidGraphEnhancementResult createAdditionalBuildables() {
    ImmutableList.Builder<BuildRule> additionalJavaLibrariesBuilder = ImmutableList.builder();

    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildTarget,
            buildTargetsToExcludeFromDex,
            resourcesToExclude,
            nativeLibsToExclude,
            nativeLinkablesToExclude,
            nativeLibAssetsToExclude,
            nativeLinkablesAssetsToExclude,
            apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(originalDeps), graphBuilder);
    AndroidPackageableCollection packageableCollection = collector.build();

    ImmutableList.Builder<SourcePath> proguardConfigsBuilder = ImmutableList.builder();
    proguardConfigsBuilder.addAll(packageableCollection.getProguardConfigs());

    AndroidNativeLibsGraphEnhancementResult nativeLibsEnhancementResult =
        nativeLibsEnhancer.enhance(packageableCollection);
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries =
        nativeLibsEnhancementResult.getCopyNativeLibraries();
    if (copyNativeLibraries.isPresent()) {
      copyNativeLibraries.get().values().forEach(graphBuilder::addToIndex);
    }

    if (nativeLibraryProguardConfigGenerator.isPresent()) {
      NativeLibraryProguardGenerator nativeLibraryProguardGenerator =
          createNativeLibraryProguardGenerator(
              copyNativeLibraries.get().values().stream()
                  .map(CopyNativeLibraries::getSourcePathToAllLibsDir)
                  .collect(ImmutableList.toImmutableList()));

      graphBuilder.addToIndex(nativeLibraryProguardGenerator);
      proguardConfigsBuilder.add(nativeLibraryProguardGenerator.getSourcePathToOutput());
    }

    if (nativeLibsEnhancementResult.getUnstrippedLibraries().isPresent()) {
      UnstrippedNativeLibraries unstrippedNativeLibraries =
          new UnstrippedNativeLibraries(
              originalBuildTarget.withAppendedFlavors(UNSTRIPPED_NATIVE_LIBRARIES_FLAVOR),
              projectFilesystem,
              buildRuleParams.withoutDeclaredDeps(),
              graphBuilder,
              nativeLibsEnhancementResult.getUnstrippedLibraries().get());
      graphBuilder.addToIndex(unstrippedNativeLibraries);
    }

    Optional<ImmutableSortedMap<String, String>> sonameMergeMap =
        nativeLibsEnhancementResult.getSonameMergeMap();
    if (sonameMergeMap.isPresent() && nativeLibraryMergeCodeGenerator.isPresent()) {
      BuildRule generatorRule = graphBuilder.getRule(nativeLibraryMergeCodeGenerator.get());

      GenerateCodeForMergedLibraryMap generateCodeForMergedLibraryMap =
          new GenerateCodeForMergedLibraryMap(
              originalBuildTarget.withAppendedFlavors(
                  GENERATE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR),
              projectFilesystem,
              buildRuleParams.withDeclaredDeps(ImmutableSortedSet.of(generatorRule)),
              sonameMergeMap.get(),
              nativeLibsEnhancementResult.getSharedObjectTargets().get(),
              generatorRule);
      graphBuilder.addToIndex(generateCodeForMergedLibraryMap);

      BuildRuleParams paramsForCompileGenCode =
          buildRuleParams.withDeclaredDeps(ImmutableSortedSet.of(generateCodeForMergedLibraryMap));
      DefaultJavaLibrary compileMergedNativeLibMapGenCode =
          DefaultJavaLibrary.rulesBuilder(
                  originalBuildTarget.withAppendedFlavors(
                      COMPILE_NATIVE_LIB_MERGE_MAP_GENERATED_CODE_FLAVOR),
                  projectFilesystem,
                  toolchainProvider,
                  paramsForCompileGenCode,
                  graphBuilder,
                  cellPathResolver,
                  new JavaConfiguredCompilerFactory(javaBuckConfig, javacFactory),
                  javaBuckConfig,
                  null)
              // Kind of a hack: override language level to 7 to allow string switch.
              // This can be removed once no one who uses this feature sets the level
              // to 6 in their .buckconfig.
              .setJavacOptions(
                  javacOptions.withLanguageLevelOptions(
                      JavacLanguageLevelOptions.builder()
                          .setSourceLevel("7")
                          .setTargetLevel("7")
                          .build()))
              .setSrcs(
                  ImmutableSortedSet.of(generateCodeForMergedLibraryMap.getSourcePathToOutput()))
              .setSourceOnlyAbisAllowed(false)
              .setDeps(
                  new JavaLibraryDeps.Builder(graphBuilder)
                      .addAllDepTargets(
                          paramsForCompileGenCode.getDeclaredDeps().get().stream()
                              .map(BuildRule::getBuildTarget)
                              .collect(Collectors.toList()))
                      .build())
              .build()
              .buildLibrary();
      graphBuilder.addToIndex(compileMergedNativeLibMapGenCode);
      additionalJavaLibrariesBuilder.add(compileMergedNativeLibMapGenCode);
    }

    AndroidBinaryResourcesGraphEnhancementResult resourcesEnhancementResult =
        androidBinaryResourcesGraphEnhancer.enhance(packageableCollection);

    // BuildConfig deps should not be added for instrumented APKs because BuildConfig.class has
    // already been added to the APK under test.
    if (packageType != PackageType.INSTRUMENTED) {
      ImmutableSortedSet<JavaLibrary> buildConfigDepsRules =
          addBuildConfigDeps(
              originalBuildTarget,
              projectFilesystem,
              packageType,
              exopackageModes,
              buildConfigValues,
              buildConfigValuesFile,
              graphBuilder,
              javac,
              javacOptions,
              packageableCollection);
      additionalJavaLibrariesBuilder.addAll(buildConfigDepsRules);
    }

    ImmutableList<BuildRule> additionalJavaLibraries = additionalJavaLibrariesBuilder.build();
    ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexedLibrariesExceptRDotJava =
        ImmutableMultimap.of();
    if (shouldPreDex) {
      preDexedLibrariesExceptRDotJava =
          createPreDexRulesForLibraries(additionalJavaLibraries, packageableCollection);
    }
    JavaLibrary compileUberRDotJava =
        createTrimAndCompileUberRDotJava(
            resourcesEnhancementResult, preDexedLibrariesExceptRDotJava);
    ImmutableCollection<DexProducedFromJavaLibrary> dexUberRDotJavaParts =
        createSplitAndDexUberRDotJava(compileUberRDotJava);

    ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexedLibraries =
        ImmutableMultimap.<APKModule, DexProducedFromJavaLibrary>builder()
            .putAll(preDexedLibrariesExceptRDotJava)
            .putAll(apkModuleGraph.getRootAPKModule(), dexUberRDotJavaParts)
            .build();

    ImmutableSet<SourcePath> classpathEntriesToDex =
        ImmutableSet.<SourcePath>builder()
            .addAll(packageableCollection.getClasspathEntriesToDex())
            .addAll(
                additionalJavaLibraries.stream()
                    .map(BuildRule::getSourcePathToOutput)
                    .collect(ImmutableList.toImmutableList()))
            .build();
    if (!ignoreAaptProguardConfig) {
      proguardConfigsBuilder.addAll(
          resourcesEnhancementResult.getAaptGeneratedProguardConfigFiles());
    }
    ImmutableList<SourcePath> proguardConfigs = proguardConfigsBuilder.build();

    Either<PreDexMerge, NonPreDexedDexBuildable> dexMergeRule;
    if (shouldPreDex) {
      dexMergeRule = Either.ofLeft(createPreDexMergeRule(preDexedLibraries));
    } else {
      dexMergeRule =
          Either.ofRight(
              createNonPredexedDexBuildable(
                  dexSplitMode,
                  rulesToExcludeFromDex,
                  xzCompressionLevel,
                  proguardConfigs,
                  packageableCollection,
                  classpathEntriesToDex,
                  compileUberRDotJava,
                  javaBuckConfig.shouldDesugarInterfaceMethods()));
    }

    return AndroidGraphEnhancementResult.builder()
        .setPackageableCollection(packageableCollection)
        .setPrimaryResourcesApkPath(resourcesEnhancementResult.getPrimaryResourcesApkPath())
        .setPrimaryApkAssetZips(resourcesEnhancementResult.getPrimaryApkAssetZips())
        .setExoResources(resourcesEnhancementResult.getExoResources())
        .setAndroidManifestPath(resourcesEnhancementResult.getAndroidManifestXml())
        .setCopyNativeLibraries(copyNativeLibraries)
        .setPackageStringAssets(resourcesEnhancementResult.getPackageStringAssets())
        .setDexMergeRule(dexMergeRule)
        .setClasspathEntriesToDex(classpathEntriesToDex)
        .setAPKModuleGraph(apkModuleGraph)
        .setModuleResourceApkPaths(resourcesEnhancementResult.getModuleResourceApkPaths())
        .build();
  }

  private JavaLibrary createTrimAndCompileUberRDotJava(
      AndroidBinaryResourcesGraphEnhancementResult resourcesEnhancementResult,
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexedLibrariesExceptRDotJava) {
    // Create rule to trim uber R.java sources.
    Collection<DexProducedFromJavaLibrary> preDexedLibrariesForResourceIdFiltering =
        trimResourceIds ? preDexedLibrariesExceptRDotJava.values() : ImmutableList.of();
    BuildRuleParams paramsForTrimUberRDotJava =
        buildRuleParams.withDeclaredDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(
                    graphBuilder.filterBuildRuleInputs(
                        resourcesEnhancementResult.getRDotJavaDir().orElse(null)))
                .addAll(preDexedLibrariesForResourceIdFiltering)
                .build());
    TrimUberRDotJava trimUberRDotJava =
        new TrimUberRDotJava(
            originalBuildTarget.withAppendedFlavors(TRIM_UBER_R_DOT_JAVA_FLAVOR),
            projectFilesystem,
            paramsForTrimUberRDotJava,
            resourcesEnhancementResult.getRDotJavaDir(),
            preDexedLibrariesForResourceIdFiltering,
            keepResourcePattern);
    graphBuilder.addToIndex(trimUberRDotJava);

    // Create rule to compile uber R.java sources.
    BuildRuleParams paramsForCompileUberRDotJava =
        buildRuleParams.withDeclaredDeps(ImmutableSortedSet.of(trimUberRDotJava));
    JavaLibrary compileUberRDotJava =
        DefaultJavaLibrary.rulesBuilder(
                originalBuildTarget.withAppendedFlavors(COMPILE_UBER_R_DOT_JAVA_FLAVOR),
                projectFilesystem,
                toolchainProvider,
                paramsForCompileUberRDotJava,
                graphBuilder,
                cellPathResolver,
                new JavaConfiguredCompilerFactory(javaBuckConfig, javacFactory),
                javaBuckConfig,
                null)
            .setJavacOptions(
                javacOptions.withLanguageLevelOptions(
                    JavacLanguageLevelOptions.builder()
                        .setSourceLevel("7")
                        .setTargetLevel("7")
                        .build()))
            .setSrcs(ImmutableSortedSet.of(trimUberRDotJava.getSourcePathToOutput()))
            .setSourceOnlyAbisAllowed(false)
            .setDeps(
                new JavaLibraryDeps.Builder(graphBuilder)
                    .addAllDepTargets(
                        paramsForCompileUberRDotJava.getDeclaredDeps().get().stream()
                            .map(BuildRule::getBuildTarget)
                            .collect(Collectors.toList()))
                    .build())
            .build()
            .buildLibrary();
    graphBuilder.addToIndex(compileUberRDotJava);
    return compileUberRDotJava;
  }

  @Nonnull
  private ImmutableCollection<DexProducedFromJavaLibrary> createSplitAndDexUberRDotJava(
      JavaLibrary compileUberRDotJava) {
    // Create rule to split the compiled R.java into multiple smaller jars.
    BuildTarget splitJarTarget =
        originalBuildTarget.withAppendedFlavors(SPLIT_UBER_R_DOT_JAVA_JAR_FLAVOR);
    SplitUberRDotJavaJar splitJar =
        new SplitUberRDotJavaJar(
            splitJarTarget,
            projectFilesystem,
            graphBuilder,
            compileUberRDotJava.getSourcePathToOutput(),
            dexSplitMode);
    graphBuilder.addToIndex(splitJar);

    // Create rules to dex uber R.java jars.
    ImmutableCollection.Builder<DexProducedFromJavaLibrary> builder = ImmutableList.builder();
    Flavor prebuiltJarFlavor = InternalFlavor.of("prebuilt_jar");
    Flavor dexFlavor = InternalFlavor.of("dexing");
    for (Entry<String, BuildTargetSourcePath> entry : splitJar.getOutputJars().entrySet()) {
      String rtype = entry.getKey();
      BuildTargetSourcePath jarPath = entry.getValue();
      InternalFlavor rtypeFlavor = InternalFlavor.of("rtype_" + rtype);
      PrebuiltJar prebuiltJar =
          new PrebuiltJar(
              splitJarTarget.withAppendedFlavors(prebuiltJarFlavor, rtypeFlavor),
              projectFilesystem,
              buildRuleParams.withDeclaredDeps(
                  ImmutableSortedSet.of(graphBuilder.getRule(jarPath))),
              graphBuilder.getSourcePathResolver(),
              jarPath,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              false,
              false);
      graphBuilder.addToIndex(prebuiltJar);

      // For the primary dex, don't scale our weight estimate.  Just try to fit it and hope for
      // the best.  For secondary dexes, scale the estimate by a factor of 8 because R.java
      // classes are relatively small but consume a lot of field-id space.  The constant value
      // 8 was determined empirically and unscientifically.
      int weightFactor =
          !dexSplitMode.isAllowRDotJavaInSecondaryDex() || rtype.equals("_primarydex") ? 1 : 8;
      DexProducedFromJavaLibrary dexJar =
          new DexProducedFromJavaLibrary(
              splitJarTarget.withAppendedFlavors(dexFlavor, rtypeFlavor, getDexFlavor(dexTool)),
              projectFilesystem,
              androidPlatformTarget,
              buildRuleParams.withDeclaredDeps(ImmutableSortedSet.of(prebuiltJar)),
              prebuiltJar,
              dexTool,
              weightFactor);
      graphBuilder.addToIndex(dexJar);
      builder.add(dexJar);
    }

    return builder.build();
  }

  private NativeLibraryProguardGenerator createNativeLibraryProguardGenerator(
      ImmutableList<SourcePath> nativeLibsDirs) {
    BuildRuleParams paramsForNativeLibraryProguardGenerator = buildRuleParams.withoutDeclaredDeps();

    return new NativeLibraryProguardGenerator(
        originalBuildTarget.withAppendedFlavors(NATIVE_LIBRARY_PROGUARD_FLAVOR),
        projectFilesystem,
        paramsForNativeLibraryProguardGenerator,
        graphBuilder,
        nativeLibsDirs,
        graphBuilder.requireRule(nativeLibraryProguardConfigGenerator.get()));
  }

  /**
   * If the user specified any android_build_config() rules, then we must add some build rules to
   * generate the production {@code BuildConfig.class} files and ensure that they are included in
   * the list of {@link AndroidPackageableCollection#getClasspathEntriesToDex}.
   */
  public static ImmutableSortedSet<JavaLibrary> addBuildConfigDeps(
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      PackageType packageType,
      EnumSet<ExopackageMode> exopackageModes,
      BuildConfigFields buildConfigValues,
      Optional<SourcePath> buildConfigValuesFile,
      ActionGraphBuilder graphBuilder,
      Javac javac,
      JavacOptions javacOptions,
      AndroidPackageableCollection packageableCollection) {
    ImmutableSortedSet.Builder<JavaLibrary> result = ImmutableSortedSet.naturalOrder();
    BuildConfigFields buildConfigConstants =
        BuildConfigFields.fromFields(
            ImmutableList.of(
                BuildConfigFields.Field.of(
                    "boolean",
                    BuildConfigs.DEBUG_CONSTANT,
                    String.valueOf(packageType != PackageType.RELEASE)),
                BuildConfigFields.Field.of(
                    "boolean",
                    BuildConfigs.IS_EXO_CONSTANT,
                    String.valueOf(!exopackageModes.isEmpty())),
                BuildConfigFields.Field.of(
                    "int",
                    BuildConfigs.EXOPACKAGE_FLAGS,
                    String.valueOf(ExopackageMode.toBitmask(exopackageModes)))));
    for (Map.Entry<String, BuildConfigFields> entry :
        packageableCollection.getBuildConfigs().entrySet()) {
      // Merge the user-defined constants with the APK-specific overrides.
      BuildConfigFields totalBuildConfigValues =
          BuildConfigFields.of()
              .putAll(entry.getValue())
              .putAll(buildConfigValues)
              .putAll(buildConfigConstants);

      // Each enhanced dep needs a unique build target, so we parameterize the build target by the
      // Java package.
      String javaPackage = entry.getKey();
      Flavor flavor = InternalFlavor.of("buildconfig_" + javaPackage.replace('.', '_'));
      BuildTarget buildTargetWithFlavors = originalBuildTarget.withAppendedFlavors(flavor);
      BuildRuleParams buildConfigParams =
          new BuildRuleParams(
              /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.of()),
              /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.of()),
              ImmutableSortedSet.of());
      JavaLibrary buildConfigJavaLibrary =
          AndroidBuildConfigDescription.createBuildRule(
              buildTargetWithFlavors,
              projectFilesystem,
              buildConfigParams,
              javaPackage,
              totalBuildConfigValues,
              buildConfigValuesFile,
              /* useConstantExpressions */ true,
              javac,
              javacOptions,
              graphBuilder);
      graphBuilder.addToIndex(buildConfigJavaLibrary);

      Preconditions.checkNotNull(
          buildConfigJavaLibrary.getSourcePathToOutput(),
          "%s must have an output file.",
          buildConfigJavaLibrary);
      result.add(buildConfigJavaLibrary);
    }
    return result.build();
  }

  /**
   * Creates/finds the set of build rules that correspond to pre-dex'd artifacts that should be
   * merged to create the final classes.dex for the APK.
   *
   * <p>This method may modify {@code graphBuilder}, inserting new rules into its index.
   */
  @VisibleForTesting
  PreDexMerge createPreDexMergeRule(
      ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> allPreDexDeps) {
    PreDexMerge preDexMerge =
        new PreDexMerge(
            originalBuildTarget.withAppendedFlavors(DEX_MERGE_FLAVOR, getDexFlavor(dexTool)),
            projectFilesystem,
            androidPlatformTarget,
            buildRuleParams.withDeclaredDeps(ImmutableSortedSet.copyOf(allPreDexDeps.values())),
            dexSplitMode,
            apkModuleGraph,
            allPreDexDeps,
            dxExecutorService,
            xzCompressionLevel,
            dxConfig.getDxMaxHeapSize(),
            dexTool);
    graphBuilder.addToIndex(preDexMerge);

    return preDexMerge;
  }

  @VisibleForTesting
  ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> createPreDexRulesForLibraries(
      Iterable<BuildRule> additionalJavaLibrariesToDex,
      AndroidPackageableCollection packageableCollection) {
    Iterable<BuildTarget> additionalJavaLibraryTargets =
        FluentIterable.from(additionalJavaLibrariesToDex).transform(BuildRule::getBuildTarget);
    ImmutableMultimap.Builder<APKModule, DexProducedFromJavaLibrary> preDexDeps =
        ImmutableMultimap.builder();
    for (BuildTarget buildTarget :
        Iterables.concat(
            packageableCollection.getJavaLibrariesToDex(), additionalJavaLibraryTargets)) {
      Preconditions.checkState(
          !buildTargetsToExcludeFromDex.contains(buildTarget),
          "JavaLibrary should have been excluded from target to dex: %s",
          buildTarget);

      BuildRule libraryRule = graphBuilder.getRule(buildTarget);

      Preconditions.checkState(libraryRule instanceof JavaLibrary);
      JavaLibrary javaLibrary = (JavaLibrary) libraryRule;

      // If the rule has no output file (which happens when a java_library has no srcs or
      // resources, but export_deps is true), then there will not be anything to dx.
      if (javaLibrary.getSourcePathToOutput() == null) {
        continue;
      }

      BuildRule preDexRule =
          graphBuilder.computeIfAbsent(
              javaLibrary.getBuildTarget().withAppendedFlavors(getDexFlavor(dexTool)),
              preDexTarget -> {
                ImmutableSortedSet<BuildRule> desugarDeps =
                    dexTool.equals(DxStep.D8)
                            && javaLibrary.isDesugarEnabled()
                            && javaLibrary.isInterfaceMethodsDesugarEnabled()
                        ? getDesugarDeps(javaLibrary, graphBuilder::getRule)
                        : null;
                BuildRuleParams paramsForPreDex =
                    buildRuleParams.withDeclaredDeps(ImmutableSortedSet.of(javaLibrary));

                return new DexProducedFromJavaLibrary(
                    preDexTarget,
                    javaLibrary.getProjectFilesystem(),
                    androidPlatformTarget,
                    paramsForPreDex,
                    javaLibrary,
                    dexTool,
                    1,
                    desugarDeps);
              });
      preDexDeps.put(
          apkModuleGraph.findModuleForTarget(buildTarget), (DexProducedFromJavaLibrary) preDexRule);
    }
    return preDexDeps.build();
  }

  /**
   * Provides {@see BuildRule} set of abi dependencies that have desugar enabled on them.
   *
   * <p>These are the deps that are required for full desugaring of default and static interface
   * methods
   */
  private static ImmutableSortedSet<BuildRule> getDesugarDeps(
      JavaLibrary javaLibrary, Function<BuildTarget, BuildRule> targetToRule) {
    ImmutableSortedSet.Builder<BuildRule> resultBuilder = ImmutableSortedSet.naturalOrder();
    for (JavaLibrary library :
        JavaLibraryClasspathProvider.getTransitiveClasspathDeps(javaLibrary)) {
      if (javaLibrary != library && library.isDesugarEnabled()) {
        library
            .getAbiJar()
            .ifPresent(buildTarget -> resultBuilder.add(targetToRule.apply(buildTarget)));
      }
    }
    return resultBuilder.build();
  }

  private NonPreDexedDexBuildable createNonPredexedDexBuildable(
      DexSplitMode dexSplitMode,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      OptionalInt xzCompressionLevel,
      ImmutableList<SourcePath> proguardConfigs,
      AndroidPackageableCollection packageableCollection,
      ImmutableSet<SourcePath> classpathEntriesToDex,
      JavaLibrary compiledUberRDotJava,
      boolean desugarInterfaceMethods) {
    ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> apkModuleMap =
        apkModuleGraph.toOutgoingEdgesMap();
    APKModule rootAPKModule = apkModuleGraph.getRootAPKModule();

    ImmutableSortedSet<SourcePath> additionalJarsForProguardandDesugar =
        rulesToExcludeFromDex.stream()
            .flatMap((javaLibrary) -> javaLibrary.getImmediateClasspaths().stream())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    Optional<ImmutableSet<SourcePath>> classpathEntriesToDexSourcePaths =
        Optional.of(
            RichStream.from(classpathEntriesToDex)
                .concat(RichStream.of(compiledUberRDotJava.getSourcePathToOutput()))
                .collect(ImmutableSet.toImmutableSet()));
    Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
        moduleMappedClasspathEntriesToDex =
            Optional.of(
                MoreMaps.convertMultimapToMapOfLists(
                    packageableCollection.getModuleMappedClasspathEntriesToDex()));
    NonPreDexedDexBuildable nonPreDexedDexBuildable =
        new NonPreDexedDexBuildable(
            androidPlatformTarget,
            graphBuilder,
            additionalJarsForProguardandDesugar,
            apkModuleMap,
            classpathEntriesToDexSourcePaths,
            dexSplitMode,
            moduleMappedClasspathEntriesToDex,
            proguardConfigs,
            rootAPKModule,
            xzCompressionLevel,
            dexSplitMode.isShouldSplitDex(),
            nonPreDexedDexBuildableArgs,
            projectFilesystem,
            originalBuildTarget.withFlavors(NON_PREDEXED_DEX_BUILDABLE_FLAVOR),
            dexTool,
            desugarInterfaceMethods);
    graphBuilder.addToIndex(nonPreDexedDexBuildable);

    if (nonPreDexedDexBuildableArgs.getShouldProguard()) {
      ProguardTextOutput proguardTextOutput =
          new ProguardTextOutput(
              originalBuildTarget.withFlavors(PROGUARD_TEXT_OUTPUT_FLAVOR),
              nonPreDexedDexBuildable,
              graphBuilder);
      graphBuilder.addToIndex(proguardTextOutput);
    }

    return nonPreDexedDexBuildable;
  }

  private static Flavor getDexFlavor(String dexTool) {
    switch (dexTool) {
      case DxStep.D8:
        return D8_FLAVOR;
      default:
        return DEX_FLAVOR;
    }
  }
}
