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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasCustomDepsLogic;
import com.facebook.buck.core.rules.modern.HasCustomInputsLogic;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.keys.AbstractRuleKeyBuilder;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.NoopRuleKeyScopedHasher;
import com.facebook.buck.util.Memoizer;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public final class BuildableSupport {
  private BuildableSupport() {}

  /** Derives dependencies based on everything added to the rulekey. */
  public static Stream<BuildRule> deriveDeps(AddsToRuleKey rule, SourcePathRuleFinder ruleFinder) {
    DepsBuilder builder = new DepsBuilder(ruleFinder);
    AlterRuleKeys.amendKey(builder, rule);
    return builder.build();
  }

  /** Derives dependencies based on everything added to the rulekey. */
  public static Stream<BuildRule> deriveDeps(BuildRule rule, SourcePathRuleFinder ruleFinder) {
    DepsBuilder builder = new DepsBuilder(ruleFinder);
    AlterRuleKeys.amendKey(builder, rule);
    return builder.build();
  }

  /** Derives dependencies based on everything added to its rulekey. */
  public static ImmutableCollection<BuildRule> getDepsCollection(
      AddsToRuleKey tool, SourcePathRuleFinder ruleFinder) {
    return getDeps(tool, ruleFinder).collect(ImmutableList.toImmutableList());
  }

  /** Streams dependencies based on everything added to its rulekey. */
  public static Stream<BuildRule> getDeps(AddsToRuleKey tool, SourcePathRuleFinder ruleFinder) {
    return deriveDeps(tool, ruleFinder);
  }

  /** Derives inputs based on everything added to the rulekey. */
  public static Stream<SourcePath> deriveInputs(AddsToRuleKey object) {
    InputsBuilder builder = new InputsBuilder();
    AlterRuleKeys.amendKey(builder, object);
    return builder.build();
  }

  /**
   * Creates a supplier to easily implement (and cache) BuildRule.getBuildDeps() via
   * BuildableSupport.deriveDeps().
   */
  public static DepsSupplier buildDepsSupplier(BuildRule rule, SourcePathRuleFinder ruleFinder) {
    return new DepsSupplier(rule, ruleFinder);
  }

  /** A build deps supplier that allows updating of the captured rule finder. */
  public static class DepsSupplier implements Supplier<SortedSet<BuildRule>> {
    private final Memoizer<SortedSet<BuildRule>> memoizer = new Memoizer<>();
    private final BuildRule rule;

    private SourcePathRuleFinder ruleFinder;

    DepsSupplier(BuildRule rule, SourcePathRuleFinder ruleFinder) {
      this.rule = rule;
      this.ruleFinder = ruleFinder;
    }

    @Override
    public SortedSet<BuildRule> get() {
      return memoizer.get(
          () ->
              deriveDeps(rule, ruleFinder)
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    }

    public void updateRuleFinder(SourcePathRuleFinder ruleFinder) {
      this.ruleFinder = ruleFinder;
    }
  }

  private static class DepsBuilder extends AbstractRuleKeyBuilder<Stream<BuildRule>> {
    private final Stream.Builder<BuildRule> streamBuilder;
    private final SourcePathRuleFinder ruleFinder;

    public DepsBuilder(SourcePathRuleFinder ruleFinder) {
      super(NoopRuleKeyScopedHasher.INSTANCE);
      this.ruleFinder = ruleFinder;
      this.streamBuilder = Stream.builder();
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setSingleValue(@Nullable Object val) {
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setBuildRule(BuildRule rule) {
      streamBuilder.accept(rule);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setAddsToRuleKey(AddsToRuleKey appendable) {
      if (appendable instanceof HasCustomDepsLogic) {
        ((HasCustomDepsLogic) appendable).getDeps(ruleFinder).forEach(streamBuilder);
      } else {
        AlterRuleKeys.amendKey(this, appendable);
      }
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setSourcePath(SourcePath sourcePath) {
      ruleFinder.getRule(sourcePath).ifPresent(streamBuilder);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<BuildRule>> setNonHashingSourcePath(
        SourcePath sourcePath) {
      ruleFinder.getRule(sourcePath).ifPresent(streamBuilder);
      return this;
    }

    @Override
    public Stream<BuildRule> build() {
      return streamBuilder.build();
    }
  }

  private static class InputsBuilder extends AbstractRuleKeyBuilder<Stream<SourcePath>> {
    private final Stream.Builder<SourcePath> streamBuilder;

    public InputsBuilder() {
      super(NoopRuleKeyScopedHasher.INSTANCE);
      this.streamBuilder = Stream.builder();
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<SourcePath>> setSingleValue(@Nullable Object val) {
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<SourcePath>> setBuildRule(BuildRule rule) {
      throw new RuntimeException("cannot derive inputs from BuildRule");
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<SourcePath>> setAddsToRuleKey(
        AddsToRuleKey appendable) {
      if (appendable instanceof HasCustomInputsLogic) {
        ((HasCustomInputsLogic) appendable).computeInputs(streamBuilder::add);
      } else {
        AlterRuleKeys.amendKey(this, appendable);
      }
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<SourcePath>> setSourcePath(SourcePath sourcePath) {
      streamBuilder.add(sourcePath);
      return this;
    }

    @Override
    protected AbstractRuleKeyBuilder<Stream<SourcePath>> setNonHashingSourcePath(
        SourcePath sourcePath) {
      streamBuilder.add(sourcePath);
      return this;
    }

    @Override
    public Stream<SourcePath> build() {
      return streamBuilder.build();
    }
  }
}
