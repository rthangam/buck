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
package com.facebook.buck.core.graph.transformation.impl;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableMap;

public class FakeComputationEnvironment extends DefaultComputationEnvironment {

  /**
   * Package protected constructor so only {@link DefaultGraphTransformationEngine} can create the
   * environment
   */
  public FakeComputationEnvironment(
      ImmutableMap<? extends ComputeKey<?>, ? extends ComputeResult> deps) {
    super(deps);
  }
}
