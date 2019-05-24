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

package com.facebook.buck.core.model.targetgraph.raw;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * A target node with attributes kept in a map as oppose to in a structured object like in {@link
 * com.facebook.buck.core.model.targetgraph.TargetNode}.
 *
 * <p>The attributes are coerced from raw data produced by build file parser, but they are not
 * stored in a structured object as in {@link com.facebook.buck.core.model.targetgraph.TargetNode}.
 *
 * <p>The main purpose of having such nodes is to perform additional processing before storing them
 * in a structured constructor arguments.
 */
public interface RawTargetNode extends ComputeResult {

  /** Build target of this node. */
  UnconfiguredBuildTarget getBuildTarget();

  /** The type of a rule. */
  RuleType getRuleType();

  /**
   * Attributes of this node coerced to the types declared in constructor arguments.
   *
   * <p>Note that some of these attributes may require additional processing before they can be
   * stored in a constructor argument. For example, selectable arguments need to be resolved first.
   */
  ImmutableMap<String, Object> getAttributes();

  /** List of patterns from <code>visibility</code> attribute. */
  ImmutableSet<VisibilityPattern> getVisibilityPatterns();

  /** List of patterns from <code>within_view</code> attribute. */
  ImmutableSet<VisibilityPattern> getWithinViewPatterns();
}
