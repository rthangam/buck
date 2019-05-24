/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

/** Matches all {@link TargetNode} objects in a repository that match the specification. */
@Value.Immutable(builder = false)
public abstract class TargetNodePredicateSpec implements TargetNodeSpec {

  @Override
  @Value.Parameter
  public abstract BuildFileSpec getBuildFileSpec();

  @Value.Default
  public boolean onlyTests() {
    return false;
  }

  @Override
  public TargetType getTargetType() {
    return TargetType.MULTIPLE_TARGETS;
  }

  @Override
  public ImmutableMap<BuildTarget, TargetNode<?>> filter(Iterable<TargetNode<?>> nodes) {
    ImmutableMap.Builder<BuildTarget, TargetNode<?>> resultBuilder = ImmutableMap.builder();

    for (TargetNode<?> node : nodes) {
      if (!onlyTests() || node.getRuleType().isTestRule()) {
        resultBuilder.put(node.getBuildTarget(), node);
      }
    }

    return resultBuilder.build();
  }
}
