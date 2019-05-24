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

package com.facebook.buck.core.rules.config;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;

/**
 * A rule that can be used to configure a build graph.
 *
 * <p>An example of such rule may be {@code config_setting} which is used together with {@code
 * select} to configure attribute values.
 */
public interface ConfigurationRule {

  /** {@link UnconfiguredBuildTargetView} that identifies this rule in a graph. */
  UnconfiguredBuildTargetView getBuildTarget();
}
