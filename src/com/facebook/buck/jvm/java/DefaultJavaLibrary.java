/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.EmptyJavaAbiInfo;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.rules.modern.PipelinedModernBuildRule;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUCK</code>:
 *
 * <pre>
 * java_library(
 *   name = 'feed',
 *   srcs = [
 *     'FeedStoryRenderer.java',
 *   ],
 *   deps = [
 *     '//src/com/facebook/feed/model:model',
 *     '//third-party/java/guava:guava',
 *   ],
 * )
 * </pre>
 *
 * Then this would compile {@code FeedStoryRenderer.java} against Guava and the classes generated
 * from the {@code //src/com/facebook/feed/model:model} rule.
 */
public class DefaultJavaLibrary
    extends PipelinedModernBuildRule<JavacPipelineState, DefaultJavaLibraryBuildable>
    implements JavaLibrary,
        HasClasspathEntries,
        HasClasspathDeps,
        ExportDependencies,
        InitializableFromDisk<JavaLibrary.Data>,
        AndroidPackageable,
        MaybeRequiredForSourceOnlyAbi,
        SupportsDependencyFileRuleKey,
        JavaLibraryWithTests {

  private final Optional<String> mavenCoords;
  @Nullable private final BuildTarget abiJar;
  @Nullable private final BuildTarget sourceOnlyAbiJar;
  private final Optional<SourcePath> proguardConfig;
  private final boolean requiredForSourceOnlyAbi;

  // It's very important that these deps are non-ABI rules, even if compiling against ABIs is turned
  // on. This is because various methods in this class perform dependency traversal that rely on
  // these deps being represented as their full-jar dependency form.
  private final SortedSet<BuildRule> firstOrderPackageableDeps;
  private final ImmutableSortedSet<BuildRule> fullJarExportedDeps;
  private final ImmutableSortedSet<BuildRule> fullJarProvidedDeps;
  private final ImmutableSortedSet<BuildRule> fullJarExportedProvidedDeps;

  private final Supplier<ImmutableSet<SourcePath>> outputClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<SourcePath>> transitiveClasspathsSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final JavaAbiInfo javaAbiInfo;

  @Nullable private CalculateSourceAbi sourceAbi;
  private final boolean isDesugarEnabled;
  private final boolean isInterfaceMethodsDesugarEnabled;
  private SourcePathRuleFinder ruleFinder;
  private final Optional<SourcePath> sourcePathForOutputJar;
  private final Optional<SourcePath> sourcePathForGeneratedAnnotationPath;

  public static DefaultJavaLibraryRules.Builder rulesBuilder(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellPathResolver,
      ConfiguredCompilerFactory compilerFactory,
      @Nullable JavaBuckConfig javaBuckConfig,
      @Nullable JavaLibraryDescription.CoreArg args) {
    return new DefaultJavaLibraryRules.Builder(
        buildTarget,
        projectFilesystem,
        toolchainProvider,
        params,
        graphBuilder,
        cellPathResolver,
        compilerFactory,
        javaBuckConfig,
        args);
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  protected DefaultJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      JarBuildStepsFactory jarBuildStepsFactory,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> proguardConfig,
      SortedSet<BuildRule> firstOrderPackageableDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedDeps,
      ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
      ImmutableSortedSet<BuildRule> fullJarExportedProvidedDeps,
      @Nullable BuildTarget abiJar,
      @Nullable BuildTarget sourceOnlyAbiJar,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests,
      boolean requiredForSourceOnlyAbi,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
      @Nullable CalculateSourceAbi sourceAbi,
      boolean isDesugarEnabled,
      boolean isInterfaceMethodsDesugarEnabled) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new DefaultJavaLibraryBuildable(
            buildTarget,
            projectFilesystem,
            jarBuildStepsFactory,
            unusedDependenciesAction,
            unusedDependenciesFinderFactory,
            sourceAbi));
    this.ruleFinder = ruleFinder;
    this.sourcePathForOutputJar =
        Optional.ofNullable(
            jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget(), getProjectFilesystem()));
    this.sourcePathForGeneratedAnnotationPath =
        Optional.ofNullable(
            jarBuildStepsFactory.getSourcePathToGeneratedAnnotationPath(
                getBuildTarget(), getProjectFilesystem()));

    this.sourceAbi = sourceAbi;
    this.isDesugarEnabled = isDesugarEnabled;
    this.isInterfaceMethodsDesugarEnabled = isInterfaceMethodsDesugarEnabled;

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    validateExportedDepsType(buildTarget, fullJarExportedDeps);
    validateExportedDepsType(buildTarget, fullJarExportedProvidedDeps);

    Sets.SetView<BuildRule> missingExports =
        Sets.difference(fullJarExportedDeps, firstOrderPackageableDeps);
    // Exports should have been copied over to declared before invoking this constructor
    Preconditions.checkState(missingExports.isEmpty());

    this.proguardConfig = proguardConfig;
    this.firstOrderPackageableDeps = firstOrderPackageableDeps;
    this.fullJarExportedDeps = fullJarExportedDeps;
    this.fullJarProvidedDeps = fullJarProvidedDeps;
    this.fullJarExportedProvidedDeps = fullJarExportedProvidedDeps;
    this.mavenCoords = mavenCoords;
    this.tests = tests;
    this.requiredForSourceOnlyAbi = requiredForSourceOnlyAbi;

    this.javaAbiInfo =
        getSourcePathToOutput() == null
            ? new EmptyJavaAbiInfo(getBuildTarget())
            : new DefaultJavaAbiInfo(getSourcePathToOutput());
    this.abiJar = abiJar;
    this.sourceOnlyAbiJar = sourceOnlyAbiJar;

    this.outputClasspathEntriesSupplier =
        MoreSuppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getOutputClasspathJars(
                    DefaultJavaLibrary.this, sourcePathForOutputJar()));

    this.transitiveClasspathsSupplier =
        MoreSuppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getClasspathsFromLibraries(
                    getTransitiveClasspathDeps()));

    this.transitiveClasspathDepsSupplier =
        MoreSuppliers.memoize(
            () -> JavaLibraryClasspathProvider.getTransitiveClasspathDeps(DefaultJavaLibrary.this));

    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  private static void validateExportedDepsType(
      BuildTarget buildTarget, ImmutableSortedSet<BuildRule> exportedDeps) {
    for (BuildRule dep : exportedDeps) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            buildTarget
                + ": exported dep "
                + dep.getBuildTarget()
                + " ("
                + dep.getType()
                + ") "
                + "must be a type of java library.");
      }
    }
  }

  @Override
  public boolean isDesugarEnabled() {
    return isDesugarEnabled;
  }

  @Override
  public boolean isInterfaceMethodsDesugarEnabled() {
    return isInterfaceMethodsDesugarEnabled;
  }

  @Override
  public boolean getRequiredForSourceOnlyAbi() {
    return requiredForSourceOnlyAbi;
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return sourcePathForOutputJar;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return getBuildable().getSources();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return getBuildable().getSources();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return getBuildable().getResources();
  }

  @Override
  public Optional<String> getResourcesRoot() {
    return getBuildable().getResourcesRoot();
  }

  @Override
  public boolean hasAnnotationProcessing() {
    return getBuildable().hasAnnotationProcessing();
  }

  @Override
  public Set<BuildRule> getDepsForTransitiveClasspathEntries() {
    return firstOrderPackageableDeps;
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return transitiveClasspathsSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    ImmutableSet.Builder<SourcePath> builder = ImmutableSet.builder();

    // Add any exported deps.
    for (BuildRule exported : getExportedDeps()) {
      if (exported instanceof JavaLibrary) {
        builder.addAll(((JavaLibrary) exported).getImmediateClasspaths());
      }
    }

    // Add ourselves to the classpath if there's a jar to be built.
    Optional<SourcePath> sourcePathForOutputJar = sourcePathForOutputJar();
    if (sourcePathForOutputJar.isPresent()) {
      builder.add(sourcePathForOutputJar.get());
    }

    return builder.build();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return outputClasspathEntriesSupplier.get();
  }

  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return getBuildable().getCompileTimeClasspathSourcePaths();
  }

  @Override
  public Optional<SourcePath> getGeneratedAnnotationSourcePath() {
    return sourcePathForGeneratedAnnotationPath;
  }

  @Override
  public SortedSet<BuildRule> getExportedDeps() {
    return fullJarExportedDeps;
  }

  @Override
  public SortedSet<BuildRule> getExportedProvidedDeps() {
    return fullJarExportedProvidedDeps;
  }

  @Override
  public void invalidateInitializeFromDiskState() {
    javaAbiInfo.invalidate();
  }

  /**
   * Instructs this rule to report the ABI it has on disk as its current ABI.
   *
   * @param pathResolver
   */
  @Override
  public JavaLibrary.Data initializeFromDisk(SourcePathResolver pathResolver) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    javaAbiInfo.load(pathResolver);
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), getProjectFilesystem());
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public final Optional<BuildTarget> getAbiJar() {
    return Optional.ofNullable(abiJar);
  }

  @Override
  public Optional<BuildTarget> getSourceOnlyAbiJar() {
    return Optional.ofNullable(sourceOnlyAbiJar);
  }

  @Override
  public JavaAbiInfo getAbiInfo() {
    return javaAbiInfo;
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    return sourcePathForOutputJar.orElse(null);
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    // TODO(jkeljo): Subtracting out provided deps is probably not the right behavior (we don't
    // do it when assembling the contents of a java_binary), but it is long-standing and projects
    // are depending upon it. The long term direction should be that we either require that
    // a dependency be present in only one list or define a strict order of precedence among
    // the lists (exported overrides deps overrides exported_provided overrides provided.)
    return AndroidPackageableCollector.getPackageableRules(
        ImmutableSortedSet.copyOf(
            Sets.difference(
                firstOrderPackageableDeps,
                Sets.union(fullJarProvidedDeps, fullJarExportedProvidedDeps))));
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    SourcePath output = getSourcePathToOutput();
    if (output != null) {
      collector.addClasspathEntry(this, output);
    }
    if (proguardConfig.isPresent()) {
      collector.addProguardConfig(getBuildTarget(), proguardConfig.get());
    }
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return getBuildable().useDependencyFileRuleKeys();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return getBuildable().getCoveredByDepFilePredicate(ruleFinder);
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return getBuildable().getExistenceOfInterestPredicate();
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) {
    return getBuildable()
        .getInputsAfterBuildingLocally(
            context, getProjectFilesystem(), ruleFinder, cellPathResolver);
  }

  @Override
  public boolean useRulePipelining() {
    return getBuildable().useRulePipelining();
  }

  @Override
  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return getBuildable().getPipelineStateFactory();
  }

  @Nullable
  @Override
  public SupportsPipelining<JavacPipelineState> getPreviousRuleInPipeline() {
    return sourceAbi;
  }
}
