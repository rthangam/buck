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

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSet;

/**
 * A computation that doesn't do anything.
 *
 * <p>It just takes a key and returns it as the value.
 */
public class NoOpComputation<T extends ComputeKey<T> & ComputeResult>
    implements GraphComputation<T, T> {

  private final ComputationIdentifier<T> identifier;

  public NoOpComputation(ComputationIdentifier<T> identifier) {
    this.identifier = identifier;
  }

  @Override
  public ComputationIdentifier<T> getIdentifier() {
    return identifier;
  }

  @Override
  public T transform(T key, ComputationEnvironment env) {
    return key;
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      T key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      T key) {
    return ImmutableSet.of();
  }
}
