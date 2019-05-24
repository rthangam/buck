/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.util.types.Pair;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Implementation of the metadata system for ActionGraphBuilders. */
final class ActionGraphBuilderMetadataCache {
  private final ActionGraphBuilder graphBuilder;
  private final TargetGraph targetGraph;
  private final LoadingCache<Pair<BuildTarget, Class<?>>, Optional<?>> metadataCache;

  ActionGraphBuilderMetadataCache(
      ActionGraphBuilder graphBuilder, TargetGraph targetGraph, int initialCapacity) {
    // Precondition (not checked): graphBuilder has the same targetGraph as the one passed in
    this.graphBuilder = graphBuilder;
    this.targetGraph = targetGraph;
    this.metadataCache =
        CacheBuilder.newBuilder().initialCapacity(initialCapacity).build(new MetadataCacheLoader());
  }

  @SuppressWarnings("unchecked")
  <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass) {
    try {
      return (Optional<T>) metadataCache.get(new Pair<>(target, metadataClass));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private final class MetadataCacheLoader
      extends CacheLoader<Pair<BuildTarget, Class<?>>, Optional<?>> {
    @Override
    public Optional<?> load(Pair<BuildTarget, Class<?>> key) {
      TargetNode<?> node = targetGraph.get(key.getFirst());
      try {
        return load(node, key.getSecond());
      } catch (Exception e) {
        throw new BuckUncheckedExecutionException(
            e, "Error loading metadata for target %s, class %s", key.getFirst(), key.getSecond());
      }
    }

    @SuppressWarnings("unchecked")
    private <T, U> Optional<U> load(TargetNode<T> node, Class<U> metadataClass) {
      T arg = node.getConstructorArg();
      if (metadataClass.isAssignableFrom(arg.getClass())) {
        return Optional.of(metadataClass.cast(arg));
      }

      BaseDescription<?> description = node.getDescription();
      if (!(description instanceof MetadataProvidingDescription)) {
        return Optional.empty();
      }
      MetadataProvidingDescription<T> metadataProvidingDescription =
          (MetadataProvidingDescription<T>) description;
      return metadataProvidingDescription.createMetadata(
          node.getBuildTarget(),
          graphBuilder,
          node.getCellNames(),
          arg,
          node.getSelectedVersions(),
          metadataClass);
    }
  }
}
