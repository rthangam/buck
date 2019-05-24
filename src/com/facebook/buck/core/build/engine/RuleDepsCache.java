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

package com.facebook.buck.core.build.engine;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.rules.BuildRule;
import java.util.SortedSet;

public interface RuleDepsCache {

  SortedSet<BuildRule> get(BuildRule rule);

  SortedSet<BuildRule> getRuntimeDeps(BuildRule rule);

  /**
   * @param buildEngineAction an action for the build engine that we want the deps for
   * @return the actions the given action depends on for build
   */
  SortedSet<BuildEngineAction> get(BuildEngineAction buildEngineAction);

  /**
   * @param buildEngineAction an action for the build engine that we want the deps for
   * @return the actions the given action depends on for executing the binary resulting from build
   */
  SortedSet<BuildEngineAction> getRuntimeDeps(BuildEngineAction buildEngineAction);
}
