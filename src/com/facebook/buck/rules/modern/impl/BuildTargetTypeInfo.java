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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;

/** TypeInfo for BuildTarget values. */
public class BuildTargetTypeInfo implements ValueTypeInfo<BuildTarget> {
  public static final ValueTypeInfo<BuildTarget> INSTANCE = new BuildTargetTypeInfo();

  @Override
  public <E extends Exception> void visit(BuildTarget value, ValueVisitor<E> visitor) throws E {
    UnconfiguredBuildTargetTypeInfo.INSTANCE.visit(value.getUnconfiguredBuildTargetView(), visitor);
    TargetConfigurationTypeInfo.INSTANCE.visit(value.getTargetConfiguration(), visitor);
  }

  @Override
  public <E extends Exception> BuildTarget create(ValueCreator<E> creator) throws E {
    UnconfiguredBuildTargetView unconfiguredBuildTargetView =
        UnconfiguredBuildTargetTypeInfo.INSTANCE.createNotNull(creator);
    TargetConfiguration targetConfiguration = creator.createTargetConfiguration();
    return unconfiguredBuildTargetView.configure(targetConfiguration);
  }
}
