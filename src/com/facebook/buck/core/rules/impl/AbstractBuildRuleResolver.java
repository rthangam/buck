/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** An abstract implementation of BuildTargetResolver that simplifies concrete implementations. */
public abstract class AbstractBuildRuleResolver implements BuildRuleResolver {

  private final SourcePathRuleFinder sourcePathRuleFinder;

  protected AbstractBuildRuleResolver() {
    sourcePathRuleFinder = new DefaultSourcePathRuleFinder(this);
  }

  @Override
  public <T> Optional<T> getRuleOptionalWithType(BuildTarget buildTarget, Class<T> cls) {
    return getRuleOptional(buildTarget)
        .map(
            rule -> {
              if (cls.isInstance(rule)) {
                return cls.cast(rule);
              } else {
                throw new HumanReadableException(
                    "Rule for target '%s' is present but not of expected type %s (got %s)",
                    buildTarget, cls, rule.getClass());
              }
            });
  }

  @Override
  public BuildRule getRule(BuildTarget buildTarget) {
    return getRuleOptional(buildTarget).orElseThrow(() -> unresolvableRuleException(buildTarget));
  }

  @Override
  public <T> T getRuleWithType(BuildTarget buildTarget, Class<T> cls) {
    return getRuleOptionalWithType(buildTarget, cls)
        .orElseThrow(() -> unresolvableRuleException(buildTarget));
  }

  private static HumanReadableException unresolvableRuleException(BuildTarget target) {
    return new HumanReadableException("Rule for target '%s' could not be resolved.", target);
  }

  @Override
  public ImmutableSortedSet<BuildRule> getAllRules(Iterable<BuildTarget> targets) {
    return Streams.stream(targets)
        .map(this::getRule)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
  }

  @Override
  public ImmutableSet<BuildRule> filterBuildRuleInputs(Iterable<? extends SourcePath> sources) {
    return sourcePathRuleFinder.filterBuildRuleInputs(sources);
  }

  @Override
  public ImmutableSet<BuildRule> filterBuildRuleInputs(SourcePath... sources) {
    return sourcePathRuleFinder.filterBuildRuleInputs(sources);
  }

  @Override
  public Stream<BuildRule> filterBuildRuleInputs(Stream<SourcePath> sources) {
    return sourcePathRuleFinder.filterBuildRuleInputs(sources);
  }

  @Override
  public Stream<BuildRule> filterBuildRuleInputs(Optional<SourcePath> sourcePath) {
    return sourcePathRuleFinder.filterBuildRuleInputs(sourcePath);
  }

  @Override
  public Optional<BuildRule> getRule(SourcePath sourcePath) {
    return sourcePathRuleFinder.getRule(sourcePath);
  }

  @Override
  public BuildRule getRule(BuildTargetSourcePath sourcePath) {
    return sourcePathRuleFinder.getRule(sourcePath);
  }

  @Override
  public SourcePathResolver getSourcePathResolver() {
    return sourcePathRuleFinder.getSourcePathResolver();
  }
}
