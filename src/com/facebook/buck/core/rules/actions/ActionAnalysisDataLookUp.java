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
package com.facebook.buck.core.rules.actions;

import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * Interface for querying for {@link ActionAnalysisData} via their unique {@link
 * ActionAnalysisData.ID}.
 */
public interface ActionAnalysisDataLookUp {

  /** @return if the given key exists in the look up */
  boolean actionExists(ActionAnalysisData.ID key);

  /** @return the {@link ActionAnalysisData} if exists, else {@code Optional.empty} */
  Optional<ActionAnalysisData> getActionOptional(ActionAnalysisData.ID key);

  /** @return all the registered {@link ActionAnalysisData} */
  ImmutableMap<ActionAnalysisData.ID, ActionAnalysisData> getRegisteredActions();
}
