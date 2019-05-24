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
package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory.ActionGraphCreationLifecycleListener;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import java.util.function.Function;

/** The factory in charge of creating the action graph depending on the construction mode. */
public interface ActionGraphFactoryDelegate {
  ActionGraphAndBuilder create(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      ActionGraphCreationLifecycleListener actionGraphCreationLifecycleListener,
      ActionGraphBuilderDecorator actionGraphBuilderDecorator);

  /**
   * Creates the base {@link ActionGraphBuilder} with potentially a decorator to be compatible with
   * the new rule analysis framework
   */
  @FunctionalInterface
  interface ActionGraphBuilderDecorator {
    ActionGraphBuilder create(
        Function<TargetNodeToBuildRuleTransformer, ActionGraphBuilder>
            actionGraphBuilderConstructor);
  }
}
