/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.graph.transformation.composition;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import java.util.Map;

/**
 * Generic transform API that given all dependencies through the {@link ComputationEnvironment},
 * computes the desired result of ResultType.
 *
 * @param <KeyInput> the key type that the {@link #transform(Map)} receive
 * @param <ResultInput> the result type that the {@link #transform(Map)} receive
 * @param <ResultType> the type of the result from this transform
 */
@FunctionalInterface
public interface Transformer<
    KeyInput extends ComputeKey<ResultInput>,
    ResultInput extends ComputeResult,
    ResultType extends ComputeResult> {

  /**
   * @param deps the results that the correspond to the keys {@link Composer} returned
   * @return the results to be aggregate into a {@link
   *     com.facebook.buck.core.graph.transformation.model.ComposedResult} by the {@link
   *     ComposedComputation}
   */
  Map<ComputeKey<ResultType>, ResultType> transform(Map<KeyInput, ResultInput> deps);
}
