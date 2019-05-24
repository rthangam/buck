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
package com.facebook.buck.core.rules.analysis;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.BuildTarget;
import org.immutables.value.Value;

/**
 * The key of a computation of the {@link com.facebook.buck.core.model.targetgraph.TargetGraph} to
 * its corresponding {@link com.google.devtools.build.lib.packages.Provider}s and {@link
 * com.facebook.buck.core.rules.actions.ActionAnalysisData}.
 *
 * <p>This key will be used to indicate which rule's analysis we are currently interested in.
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class RuleAnalysisKey implements ComputeKey<RuleAnalysisResult> {

  public static final ComputationIdentifier<RuleAnalysisResult> IDENTIFIER =
      ClassBasedComputationIdentifier.of(RuleAnalysisKey.class, RuleAnalysisResult.class);

  /**
   * TODO(bobyf) this really should be a ConfiguredBuildTarget
   *
   * @return the {@link BuildTarget} of this key
   */
  @Value.Parameter
  public abstract BuildTarget getBuildTarget();

  @Override
  public ComputationIdentifier<RuleAnalysisResult> getIdentifier() {
    return IDENTIFIER;
  }
}
