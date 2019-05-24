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
package com.facebook.buck.core.description.arg;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.google.common.collect.ImmutableList;

/**
 * This interface indicates that users can declare constraints and platforms that needs to be
 * matched by a target platform in order to build a particular target.
 *
 * <p>This can be used to filter out targets that cannot be built for a particular target platform.
 */
public interface HasTargetCompatibleWith {
  @Hint(isDep = false)
  ImmutableList<BuildTarget> getTargetCompatibleWith();

  /** A list of platforms a target is compatible with. */
  @Hint(isDep = false, isConfigurable = false)
  ImmutableList<UnconfiguredBuildTargetView> getTargetCompatiblePlatforms();
}
