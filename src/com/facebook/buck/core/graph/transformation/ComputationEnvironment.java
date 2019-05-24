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

package com.facebook.buck.core.graph.transformation;

import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableMap;

/**
 * A computation environment that {@link GraphComputation} can access. This class provides the
 * execution context for the {@link GraphComputation}, such as the dependencies required for this
 * transformation.
 */
public interface ComputationEnvironment {

  /**
   * @return an immutable map containing the requested deps and their results. The dependencies here
   *     are all of the keys returned from {@link GraphComputation#discoverDeps}
   */
  ImmutableMap<? extends ComputeKey<?>, ? extends ComputeResult> getDeps();

  /**
   * @param key the key requested
   * @param <KeyType> the type of the key
   * @param <ResultType> the corresponding result type
   * @return a casted result of the specific key
   */
  <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult> ResultType getDep(
      KeyType key);

  /**
   * @param <KeyType> the type of the key
   * @param <ResultType> the corresponding result type
   * @param identifier the identifier of the keys
   * @return a casted result of all the keys value pairs of the given class
   */
  <KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
      ImmutableMap<KeyType, ResultType> getDeps(ComputationIdentifier<ResultType> identifier);
}
