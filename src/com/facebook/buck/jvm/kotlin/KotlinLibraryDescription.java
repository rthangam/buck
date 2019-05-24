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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.MavenUberJar;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.maven.aether.AetherUtil;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

public class KotlinLibraryDescription
    implements DescriptionWithTargetGraph<KotlinLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<KotlinLibraryDescriptionArg>,
        Flavored {
  public static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(JavaLibrary.SRC_JAR, JavaLibrary.MAVEN_JAR);

  private final ToolchainProvider toolchainProvider;
  private final KotlinBuckConfig kotlinBuckConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final JavacFactory javacFactory;

  public KotlinLibraryDescription(
      ToolchainProvider toolchainProvider,
      KotlinBuckConfig kotlinBuckConfig,
      JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.javacFactory = JavacFactory.getDefault(toolchainProvider);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
  }

  @Override
  public Class<KotlinLibraryDescriptionArg> getConstructorArgType() {
    return KotlinLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      KotlinLibraryDescriptionArg args) {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();

    BuildTarget buildTargetWithMavenFlavor = null;
    BuildRuleParams paramsWithMavenFlavor = null;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {
      buildTargetWithMavenFlavor = buildTarget;
      paramsWithMavenFlavor = params;

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      buildTarget = buildTarget.withoutFlavors(JavaLibrary.MAVEN_JAR);
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      Optional<String> mavenCoords =
          args.getMavenCoords()
              .map(input -> AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES));

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            buildTarget, projectFilesystem, params, args.getSrcs(), mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            buildTargetWithMavenFlavor,
            projectFilesystem,
            Objects.requireNonNull(paramsWithMavenFlavor),
            args.getSrcs(),
            mavenCoords,
            args.getMavenPomTemplate());
      }
    }
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            graphBuilder,
            args);

    DefaultJavaLibraryRules defaultKotlinLibraryBuilder =
        KotlinLibraryBuilder.newInstance(
                buildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                graphBuilder,
                context.getCellPathResolver(),
                kotlinBuckConfig,
                javaBuckConfig,
                args,
                javacFactory)
            .setJavacOptions(javacOptions)
            .build();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.
    if (JavaAbis.isAbiTarget(buildTarget)) {
      return defaultKotlinLibraryBuilder.buildAbi();
    }

    DefaultJavaLibrary defaultKotlinLibrary = defaultKotlinLibraryBuilder.buildLibrary();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultKotlinLibrary;
    } else {
      graphBuilder.addToIndex(defaultKotlinLibrary);
      return MavenUberJar.create(
          defaultKotlinLibrary,
          buildTargetWithMavenFlavor,
          projectFilesystem,
          Objects.requireNonNull(paramsWithMavenFlavor),
          args.getMavenCoords(),
          args.getMavenPomTemplate());
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      KotlinLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javacFactory.addParseTimeDeps(targetGraphOnlyDepsBuilder, constructorArg);
  }

  public enum AnnotationProcessingTool {
    /**
     * Default tool for Kotlin modules. Allows to run Java annotation processors against Kotlin
     * sources while backporting it for Java sources too.
     */
    KAPT,

    /**
     * Works only against Java sources, Kotlin sources won't have access to generated classes at
     * compile time.
     */
    JAVAC,
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    ImmutableList<String> getExtraKotlincArguments();

    Optional<AnnotationProcessingTool> getAnnotationProcessingTool();

    ImmutableList<SourcePath> getFriendPaths();

    ImmutableMap<String, String> getKaptApOptions();

    ImmutableList<SourcePath> getKotlincPlugins();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractKotlinLibraryDescriptionArg extends CoreArg {}
}
