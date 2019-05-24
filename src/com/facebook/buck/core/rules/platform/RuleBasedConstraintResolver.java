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

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.HostConstraintDetector;
import com.facebook.buck.core.model.platform.ProvidesHostConstraintDetector;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Optional;

/**
 * {@link ConstraintResolver} that uses configuration rules obtained from {@link
 * ConfigurationRuleResolver} to create {@link ConstraintSetting} and {@link ConstraintValue}
 * instances.
 *
 * <p>All instances are cached.
 */
public class RuleBasedConstraintResolver implements ConstraintResolver {
  private final ConfigurationRuleResolver configurationRuleResolver;

  private final LoadingCache<UnconfiguredBuildTargetView, HostConstraintDetector>
      constraintDetectorCache =
          CacheBuilder.newBuilder()
              .build(
                  new CacheLoader<UnconfiguredBuildTargetView, HostConstraintDetector>() {
                    @Override
                    public HostConstraintDetector load(UnconfiguredBuildTargetView buildTarget) {
                      ConfigurationRule configurationRule =
                          configurationRuleResolver.getRule(buildTarget);
                      Preconditions.checkState(
                          configurationRule instanceof ProvidesHostConstraintDetector,
                          "%s is used as host_constraint_detector, but has wrong type",
                          buildTarget);
                      return ((ProvidesHostConstraintDetector) configurationRule)
                          .getHostConstraintDetector();
                    }
                  });

  private final LoadingCache<UnconfiguredBuildTargetView, ConstraintSetting>
      constraintSettingCache =
          CacheBuilder.newBuilder()
              .build(
                  new CacheLoader<UnconfiguredBuildTargetView, ConstraintSetting>() {
                    @Override
                    public ConstraintSetting load(UnconfiguredBuildTargetView buildTarget) {
                      ConfigurationRule configurationRule =
                          configurationRuleResolver.getRule(buildTarget);
                      Preconditions.checkState(
                          configurationRule instanceof ConstraintSettingRule,
                          "%s is used as constraint_setting, but has wrong type",
                          buildTarget);
                      Optional<UnconfiguredBuildTargetView> constraintDetectorTarget =
                          ((ConstraintSettingRule) configurationRule).getHostConstraintDetector();

                      return ConstraintSetting.of(
                          buildTarget,
                          constraintDetectorTarget.map(constraintDetectorCache::getUnchecked));
                    }
                  });

  private final LoadingCache<UnconfiguredBuildTargetView, ConstraintValue> constraintValueCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<UnconfiguredBuildTargetView, ConstraintValue>() {
                @Override
                public ConstraintValue load(UnconfiguredBuildTargetView buildTarget) {
                  ConfigurationRule configurationRule =
                      configurationRuleResolver.getRule(buildTarget);
                  Preconditions.checkState(
                      configurationRule instanceof ConstraintValueRule,
                      "%s is used as constraint_value, but has wrong type",
                      buildTarget);

                  ConstraintValueRule constraintValueRule = (ConstraintValueRule) configurationRule;

                  return ConstraintValue.of(
                      buildTarget,
                      getConstraintSetting(constraintValueRule.getConstraintSetting()));
                }
              });

  public RuleBasedConstraintResolver(ConfigurationRuleResolver configurationRuleResolver) {
    this.configurationRuleResolver = configurationRuleResolver;
  }

  @Override
  public ConstraintSetting getConstraintSetting(UnconfiguredBuildTargetView buildTarget) {
    try {
      return constraintSettingCache.getUnchecked(buildTarget);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e.getCause());
    }
  }

  @Override
  public ConstraintValue getConstraintValue(UnconfiguredBuildTargetView buildTarget) {
    try {
      return constraintValueCache.getUnchecked(buildTarget);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e.getCause());
    }
  }
}
