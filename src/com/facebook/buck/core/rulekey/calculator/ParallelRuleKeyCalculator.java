/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.rulekey.calculator;

import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.collect.SortedSets;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

/** Calculates {@link RuleKey}, bottom-up, using tree parallelism. */
public class ParallelRuleKeyCalculator<T> {

  private final ListeningExecutorService service;
  private final RuleKeyFactory<T> ruleKeyFactory;
  private final RuleDepsCache ruleDepsCache;
  private final BiFunction<BuckEventBus, BuildRule, Scope> ruleKeyCalculationScope;

  private final ConcurrentMap<BuildTarget, ListenableFuture<T>> ruleKeys = Maps.newConcurrentMap();

  public ParallelRuleKeyCalculator(
      ListeningExecutorService service,
      RuleKeyFactory<T> ruleKeyFactory,
      RuleDepsCache ruleDepsCache,
      BiFunction<BuckEventBus, BuildRule, Scope> ruleKeyCalculationScope) {
    this.service = service;
    this.ruleKeyFactory = ruleKeyFactory;
    this.ruleDepsCache = ruleDepsCache;
    this.ruleKeyCalculationScope = ruleKeyCalculationScope;
  }

  /**
   * @return a {@link ListenableFuture} wrapping the result of calculating the {@link RuleKey} of
   *     the given {@link BuildRule}.
   */
  public ListenableFuture<T> calculate(BuckEventBus buckEventBus, BuildRule rule) {

    // Do an initial check for an existing future, to avoid allocating a `SettableFuture` (below).
    ListenableFuture<T> existingFuture = ruleKeys.get(rule.getBuildTarget());
    if (existingFuture != null) {
      return existingFuture;
    }

    // Create a `SettableFuture` and atomically put it in the rule key map.  At this point, if it's
    // not already created, we should proceed.
    SettableFuture<T> future = SettableFuture.create();
    existingFuture = ruleKeys.putIfAbsent(rule.getBuildTarget(), future);
    if (existingFuture != null) {
      return existingFuture;
    }

    T fromInternalCache = ruleKeyFactory.getFromCache(rule);
    if (fromInternalCache != null) {
      // Record the rule key future.
      future.set(fromInternalCache);
      // Because a rule key will be invalidated from the internal cache any time one of its
      // dependents is invalidated, we know that all of our transitive deps are also in cache.
      return future;
    }

    // Grab all the dependency rule key futures.  Since our rule key calculation depends on this
    // one, we need to wait for them to complete.
    ListenableFuture<List<T>> depKeys =
        Futures.transformAsync(
            Futures.immediateFuture(ruleDepsCache.get(rule)),
            (@Nonnull SortedSet<BuildRule> deps) -> {
              List<ListenableFuture<T>> depKeys1 =
                  new ArrayList<>(SortedSets.sizeEstimate(rule.getBuildDeps()));
              for (BuildRule dep : deps) {
                depKeys1.add(calculate(buckEventBus, dep));
              }
              return Futures.allAsList(depKeys1);
            },
            service);

    // Setup a future to calculate this rule key once the dependencies have been calculated.
    ListenableFuture<T> calculated =
        Futures.transform(
            depKeys,
            (List<T> input) -> {
              try (Scope scope = ruleKeyCalculationScope.apply(buckEventBus, rule)) {
                return ruleKeyFactory.build(rule);
              } catch (Exception e) {
                throw new BuckUncheckedExecutionException(
                    e, String.format("When computing rulekey for %s.", rule));
              }
            },
            service);

    future.setFuture(calculated);
    return future;
  }

  public Set<BuildTarget> getAllKnownTargets() {
    return ruleKeys.keySet();
  }

  public RuleDepsCache getRuleDepsCache() {
    return ruleDepsCache;
  }
}
