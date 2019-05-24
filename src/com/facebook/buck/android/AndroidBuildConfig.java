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

package com.facebook.buck.android;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * {@link BuildRule} that can generate a {@code BuildConfig.java} file and compile it so it can be
 * used as a Java library.
 *
 * <p>This rule functions as a {@code java_library} that can be used as a dependency of an {@code
 * android_library}, but whose implementation may be swapped out by the {@code android_binary} that
 * transitively includes the {@code android_build_config}. Specifically, its compile-time
 * implementation will use non-constant-expression (see JLS 15.28), placeholder values (because they
 * cannot be inlined) for the purposes of compilation that will be swapped out with final,
 * production values (that can be inlined) when building the final APK. Consider the following
 * example:
 *
 * <pre>
 * android_build_config(
 *   name = 'build_config',
 *   package = 'com.example.pkg',
 * )
 *
 * # The .java files in this library may contain references to the boolean
 * # com.example.pkg.BuildConfig.DEBUG because :build_config is in the deps.
 * android_library(
 *   name = 'mylib',
 *   srcs = glob(['src/**&#47;*.java']),
 *   deps = [
 *     ':build_config',
 *   ],
 * )
 *
 * android_binary(
 *   name = 'debug',
 *   package_type = 'DEBUG',
 *   keystore =  '//keystores:debug',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:19',
 *   deps = [
 *     ':mylib',
 *   ],
 * )
 *
 * android_binary(
 *   name = 'release',
 *   package_type = 'RELEASE',
 *   keystore =  '//keystores:release',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:19',
 *   deps = [
 *     ':mylib',
 *   ],
 * )
 * </pre>
 *
 * The {@code :mylib} rule will be compiled against a version of {@code BuildConfig.java} whose
 * contents are:
 *
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = !Boolean.parseBoolean(null);
 * }
 * </pre>
 *
 * Note that the value is not a constant expression, so it cannot be inlined by {@code javac}. When
 * building {@code :debug} and {@code :release}, the {@code BuildConfig.class} file that {@code
 * :mylib} was compiled against will not be included in the APK as the other transitive Java deps of
 * the {@code android_binary} will. The {@code BuildConfig.class} will be replaced with one that
 * corresponds to the value of the {@code package_type} argument to the {@code android_binary} rule.
 * For example, {@code :debug} will include a {@code BuildConfig.class} file that is compiled from:
 *
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = true;
 * }
 * </pre>
 *
 * whereas {@code :release} will include a {@code BuildConfig.class} file that is compiled from:
 *
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = false;
 * }
 * </pre>
 *
 * This swap happens before ProGuard is run as part of building the APK, so it will be able to
 * exploit the "final-ness" of the {@code DEBUG} constant in any whole-program optimization that it
 * performs.
 */
public class AndroidBuildConfig extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final String javaPackage;

  @AddToRuleKey(stringify = true)
  private final BuildConfigFields defaultValues;

  @AddToRuleKey private final Optional<SourcePath> valuesFile;
  @AddToRuleKey private final boolean useConstantExpressions;
  private final Path pathToOutputFile;

  protected AndroidBuildConfig(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      String javaPackage,
      BuildConfigFields defaultValues,
      Optional<SourcePath> valuesFile,
      boolean useConstantExpressions) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.javaPackage = javaPackage;
    this.defaultValues = defaultValues;
    this.valuesFile = valuesFile;
    this.useConstantExpressions = useConstantExpressions;
    this.pathToOutputFile =
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "__%s__")
            .resolve("BuildConfig.java");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Supplier<BuildConfigFields> totalFields;
    if (valuesFile.isPresent()) {
      ReadValuesStep readValuesStep =
          new ReadValuesStep(
              getProjectFilesystem(),
              context.getSourcePathResolver().getAbsolutePath(valuesFile.get()));
      steps.add(readValuesStep);
      totalFields = MoreSuppliers.memoize(() -> defaultValues.putAll(readValuesStep.get()));
    } else {
      totalFields = Suppliers.ofInstance(defaultValues);
    }

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                pathToOutputFile.getParent())));
    steps.add(
        new GenerateBuildConfigStep(
            getProjectFilesystem(),
            getBuildTarget().getUnflavoredBuildTarget(),
            javaPackage,
            useConstantExpressions,
            totalFields,
            pathToOutputFile));

    buildableContext.recordArtifact(pathToOutputFile);
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutputFile);
  }

  public String getJavaPackage() {
    return javaPackage;
  }

  public boolean isUseConstantExpressions() {
    return useConstantExpressions;
  }

  public BuildConfigFields getBuildConfigFields() {
    return defaultValues;
  }

  @VisibleForTesting
  static class ReadValuesStep extends AbstractExecutionStep implements Supplier<BuildConfigFields> {

    private final ProjectFilesystem filesystem;
    private final Path valuesFile;

    @Nullable private BuildConfigFields values;

    public ReadValuesStep(ProjectFilesystem filesystem, Path valuesFile) {
      super("read values from " + valuesFile);
      this.filesystem = filesystem;
      this.valuesFile = valuesFile;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context) throws IOException {
      values = BuildConfigFields.fromFieldDeclarations(filesystem.readLines(valuesFile));
      return StepExecutionResults.SUCCESS;
    }

    @Override
    public BuildConfigFields get() {
      return Objects.requireNonNull(values);
    }
  }
}
