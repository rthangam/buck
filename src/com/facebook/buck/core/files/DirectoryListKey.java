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

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Transformation key that has info about directory listing computation */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class DirectoryListKey implements ComputeKey<DirectoryList> {

  public static final ComputationIdentifier<DirectoryList> IDENTIFIER =
      ClassBasedComputationIdentifier.of(DirectoryListKey.class, DirectoryList.class);

  @Value.Parameter
  /** Path of the directory to list */
  public abstract Path getPath();

  @Override
  public ComputationIdentifier<DirectoryList> getIdentifier() {
    return IDENTIFIER;
  }

  // sergeyb: do we need cell identifier?
  // sergeyb: do we need filesystem identifier?
}
