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

package com.facebook.buck.core.rules.config.impl;

import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescriptionProvider;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.List;
import org.pf4j.PluginManager;

/**
 * This factory creates a list of {@link ConfigurationRuleDescription} by loading instances of
 * {@link ConfigurationRuleDescriptionProvider} using plugin framework and collecting {@link
 * ConfigurationRuleDescription} from the loaded providers.
 */
public class PluginBasedKnownConfigurationDescriptionsFactory {

  public static ImmutableList<ConfigurationRuleDescription<?>> createFromPlugins(
      PluginManager pluginManager) {

    List<ConfigurationRuleDescriptionProvider> descriptionProviders =
        pluginManager.getExtensions(ConfigurationRuleDescriptionProvider.class);

    return descriptionProviders.stream()
        .map(ConfigurationRuleDescriptionProvider::getDescriptions)
        .flatMap(Collection::stream)
        .collect(ImmutableList.toImmutableList());
  }
}
