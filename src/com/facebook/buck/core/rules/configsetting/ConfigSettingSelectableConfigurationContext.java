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

package com.facebook.buck.core.rules.configsetting;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.select.SelectableConfigurationContext;

/** {@link SelectableConfigurationContext} for {@link ConfigSettingSelectable}. */
public interface ConfigSettingSelectableConfigurationContext
    extends SelectableConfigurationContext {
  BuckConfig getBuckConfig();

  ConstraintResolver getConstraintResolver();

  TargetConfiguration getTargetConfiguration();

  TargetPlatformResolver getPlatformProvider();
}
