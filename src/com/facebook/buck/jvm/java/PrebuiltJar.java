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
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.function.Supplier;

public class PrebuiltJar extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements AndroidPackageable,
        ExportDependencies,
        HasClasspathEntries,
        InitializableFromDisk<JavaLibrary.Data>,
        JavaLibrary,
        MaybeRequiredForSourceOnlyAbi,
        SupportsInputBasedRuleKey {

  @AddToRuleKey private final SourcePath binaryJar;
  private final JavaAbiInfo javaAbiInfo;
  private final Path copiedBinaryJar;
  @AddToRuleKey private final Optional<SourcePath> sourceJar;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final Optional<SourcePath> gwtJar;

  @AddToRuleKey private final Optional<String> javadocUrl;
  @AddToRuleKey private final Optional<String> mavenCoords;
  @AddToRuleKey private final boolean provided;
  @AddToRuleKey private final boolean requiredForSourceOnlyAbi;
  private final Supplier<ImmutableSet<SourcePath>> transitiveClasspathsSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;

  public PrebuiltJar(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath binaryJar,
      Optional<SourcePath> sourceJar,
      Optional<SourcePath> gwtJar,
      Optional<String> javadocUrl,
      Optional<String> mavenCoords,
      boolean provided,
      boolean requiredForSourceOnlyAbi) {
    super(buildTarget, projectFilesystem, params);
    this.binaryJar = binaryJar;
    this.sourceJar = sourceJar;
    this.gwtJar = gwtJar;
    this.javadocUrl = javadocUrl;
    this.mavenCoords = mavenCoords;
    this.provided = provided;
    this.requiredForSourceOnlyAbi = requiredForSourceOnlyAbi;

    transitiveClasspathsSupplier =
        MoreSuppliers.memoize(
            () ->
                JavaLibraryClasspathProvider.getClasspathsFromLibraries(
                    getTransitiveClasspathDeps()));

    this.transitiveClasspathDepsSupplier =
        MoreSuppliers.memoize(
            () -> {
              if (provided) {
                return JavaLibraryClasspathProvider.getClasspathDeps(
                    PrebuiltJar.this.getDeclaredDeps());
              }
              return ImmutableSet.<JavaLibrary>builder()
                  .add(PrebuiltJar.this)
                  .addAll(
                      JavaLibraryClasspathProvider.getClasspathDeps(
                          PrebuiltJar.this.getDeclaredDeps()))
                  .build();
            });

    Path fileName = resolver.getRelativePath(binaryJar).getFileName();
    String fileNameWithJarExtension =
        String.format("%s.jar", MorePaths.getNameWithoutExtension(fileName));
    copiedBinaryJar =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(), getBuildTarget(), "__%s__/" + fileNameWithJarExtension);
    this.javaAbiInfo = new DefaultJavaAbiInfo(getSourcePathToOutput());

    buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public boolean getRequiredForSourceOnlyAbi() {
    return requiredForSourceOnlyAbi;
  }

  public Optional<SourcePath> getSourceJar() {
    return sourceJar;
  }

  public Optional<String> getJavadocUrl() {
    return javadocUrl;
  }

  @Override
  public boolean isDesugarEnabled() {
    return true;
  }

  @Override
  public boolean isInterfaceMethodsDesugarEnabled() {
    return false;
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  public void invalidateInitializeFromDiskState() {
    javaAbiInfo.invalidate();
  }

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
  public Set<BuildRule> getDepsForTransitiveClasspathEntries() {
    return getBuildDeps();
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
    if (!provided) {
      return ImmutableSet.of(getSourcePathToOutput());
    } else {
      return ImmutableSet.of();
    }
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return ImmutableSet.of(getSourcePathToOutput());
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return ImmutableSortedSet.of();
  }

  @Override
  public Optional<String> getResourcesRoot() {
    return Optional.empty();
  }

  @Override
  public SortedSet<BuildRule> getExportedDeps() {
    return getDeclaredDeps();
  }

  @Override
  public SortedSet<BuildRule> getExportedProvidedDeps() {
    return ImmutableSortedSet.of();
  }

  @Override
  public Optional<SourcePath> getGeneratedAnnotationSourcePath() {
    return Optional.empty();
  }

  @Override
  public boolean hasAnnotationProcessing() {
    return false;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    SourcePathResolver resolver = context.getSourcePathResolver();

    // Create a copy of the JAR in case it was generated by another rule.
    Path resolvedBinaryJar = resolver.getAbsolutePath(binaryJar);
    Path resolvedCopiedBinaryJar = getProjectFilesystem().resolve(copiedBinaryJar);
    Preconditions.checkState(
        !resolvedBinaryJar.equals(resolvedCopiedBinaryJar),
        "%s: source (%s) can't be equal to destination (%s) when copying prebuilt JAR.",
        getBuildTarget().getFullyQualifiedName(),
        resolvedBinaryJar,
        copiedBinaryJar);

    if (resolver.getFilesystem(binaryJar).isDirectory(resolvedBinaryJar)) {
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), copiedBinaryJar)));
      steps.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              resolvedBinaryJar,
              copiedBinaryJar,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    } else {
      if (!MorePaths.getFileExtension(copiedBinaryJar.getFileName())
          .equals(MorePaths.getFileExtension(resolvedBinaryJar))) {
        context
            .getEventBus()
            .post(
                ConsoleEvent.warning(
                    "Assuming %s is a JAR and renaming to %s in %s. "
                        + "Change the extension of the binary_jar to '.jar' to remove this warning.",
                    resolvedBinaryJar.getFileName(),
                    copiedBinaryJar.getFileName(),
                    getBuildTarget().getFullyQualifiedName()));
      }

      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  copiedBinaryJar.getParent())));
      steps.add(CopyStep.forFile(getProjectFilesystem(), resolvedBinaryJar, copiedBinaryJar));
    }
    buildableContext.recordArtifact(copiedBinaryJar);

    Path pathToClassHashes =
        JavaLibraryRules.getPathToClassHashes(getBuildTarget(), getProjectFilesystem());
    buildableContext.recordArtifact(pathToClassHashes);

    JavaLibraryRules.addAccumulateClassNamesStep(
        ModernBuildableSupport.newCellRelativePathFactory(
            context.getBuildCellRootPath(), getProjectFilesystem()),
        getProjectFilesystem(),
        steps,
        Optional.of(context.getSourcePathResolver().getRelativePath(getSourcePathToOutput())),
        pathToClassHashes);

    return steps.build();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return AndroidPackageableCollector.getPackageableRules(getDeclaredDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (!provided) {
      collector.addClasspathEntry(this, getSourcePathToOutput());
      collector.addPathToThirdPartyJar(getBuildTarget(), getSourcePathToOutput());
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), copiedBinaryJar);
  }

  @Override
  public JavaAbiInfo getAbiInfo() {
    return javaAbiInfo;
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }
}
