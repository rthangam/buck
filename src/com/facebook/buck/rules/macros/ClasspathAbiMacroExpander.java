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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Used to expand the macro {@literal $(classpath_abi //some:target)} to the transitive abi's jars
 * path of that target, expanding all paths to be absolute.
 */
public class ClasspathAbiMacroExpander extends BuildTargetMacroExpander<ClasspathAbiMacro> {

  @Override
  public Class<ClasspathAbiMacro> getInputClass() {
    return ClasspathAbiMacro.class;
  }

  private HasClasspathEntries getHasClasspathEntries(BuildRule rule) throws MacroException {
    if (!(rule instanceof HasClasspathEntries)) {
      throw new MacroException(
          String.format(
              "%s used in classpath_abi macro does not correspond to a rule with a java classpath",
              rule.getBuildTarget()));
    }
    return (HasClasspathEntries) rule;
  }

  /**
   * Get the class abi jar if present for the rule otherwise return the rule's output
   *
   * @param rule The rule whose jar path needs to be returned
   * @return class abi jar or output jar if not found
   */
  @Nullable
  private SourcePath getJarPath(BuildRule rule, ActionGraphBuilder graphBuilder) {
    SourcePath jarPath = null;

    if (rule instanceof HasJavaAbi) {
      HasJavaAbi javaAbiRule = (HasJavaAbi) rule;
      Optional<BuildTarget> optionalBuildTarget = javaAbiRule.getAbiJar();
      if (optionalBuildTarget.isPresent()) {
        jarPath = graphBuilder.requireRule(optionalBuildTarget.get()).getSourcePathToOutput();
      }
    }

    if (jarPath == null) {
      jarPath = rule.getSourcePathToOutput();
    }

    return jarPath;
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, ClasspathAbiMacro macro, BuildRule rule)
      throws MacroException {
    throw new MacroException(
        "expand(BuildRuleResolver ruleResolver, ClasspathAbiMacro input) should be called instead");
  }

  @Override
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ClasspathAbiMacro input)
      throws MacroException {

    BuildRule inputRule = resolve(graphBuilder, input);
    return expand(graphBuilder, inputRule);
  }

  protected Arg expand(ActionGraphBuilder graphBuilder, BuildRule inputRule) throws MacroException {

    ImmutableList<SourcePath> jarPaths =
        getHasClasspathEntries(inputRule).getTransitiveClasspathDeps().stream()
            .filter(d -> d.getSourcePathToOutput() != null)
            .map(d -> getJarPath(d, graphBuilder))
            .filter(Objects::nonNull)
            .sorted()
            .collect(ImmutableList.toImmutableList());

    return new AbiJarPathArg(jarPaths);
  }

  private class AbiJarPathArg implements Arg {

    @AddToRuleKey private final ImmutableList<SourcePath> classpath;

    AbiJarPathArg(ImmutableList<SourcePath> collect) {
      this.classpath = collect;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      consumer.accept(
          classpath.stream()
              .map(pathResolver::getAbsolutePath)
              .map(Object::toString)
              .sorted(Ordering.natural())
              .collect(Collectors.joining(File.pathSeparator)));
    }
  }
}
