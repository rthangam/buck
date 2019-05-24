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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

public class RawTargetNodeToTargetNodeParsePipeline
    extends ConvertingPipeline<RawTargetNode, TargetNode<?>> {

  private static final Logger LOG = Logger.get(RawTargetNodeToTargetNodeParsePipeline.class);

  private final boolean speculativeDepsTraversal;
  private final RawTargetNodePipeline rawTargetNodePipeline;
  private final ParserTargetNodeFactory<RawTargetNode> rawTargetNodeToTargetNodeFactory;

  /** Create new pipeline for parsing Buck files. */
  public RawTargetNodeToTargetNodeParsePipeline(
      Cache<BuildTarget, TargetNode<?>> cache,
      ListeningExecutorService executorService,
      RawTargetNodePipeline rawTargetNodePipeline,
      BuckEventBus eventBus,
      String pipelineName,
      boolean speculativeDepsTraversal,
      ParserTargetNodeFactory<RawTargetNode> rawTargetNodeToTargetNodeFactory) {
    super(
        executorService,
        cache,
        eventBus,
        SimplePerfEvent.scope(eventBus, PerfEventId.of(pipelineName)),
        PerfEventId.of("GetTargetNode"));
    this.rawTargetNodePipeline = rawTargetNodePipeline;
    this.speculativeDepsTraversal = speculativeDepsTraversal;
    this.rawTargetNodeToTargetNodeFactory = rawTargetNodeToTargetNodeFactory;
  }

  @Override
  protected BuildTarget getBuildTarget(
      Path root,
      Optional<String> cellName,
      Path buildFile,
      TargetConfiguration targetConfiguration,
      RawTargetNode from) {
    return ImmutableUnconfiguredBuildTargetView.of(root, from.getBuildTarget())
        .configure(targetConfiguration);
  }

  @Override
  @SuppressWarnings("CheckReturnValue") // submit result is not used
  protected TargetNode<?> computeNodeInScope(
      Cell cell,
      BuildTarget buildTarget,
      RawTargetNode rawNode,
      Function<PerfEventId, Scope> perfEventScopeFunction)
      throws BuildTargetException {
    TargetNode<?> targetNode =
        rawTargetNodeToTargetNodeFactory.createTargetNode(
            cell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(cell, buildTarget.getUnconfiguredBuildTargetView()),
            buildTarget,
            rawNode,
            perfEventScopeFunction);

    if (speculativeDepsTraversal) {
      executorService.submit(
          () -> {
            for (BuildTarget depTarget : targetNode.getParseDeps()) {
              Cell depCell = cell.getCellIgnoringVisibilityCheck(depTarget.getCellPath());
              try {
                if (depTarget.isFlavored()) {
                  getNodeJob(depCell, depTarget.withoutFlavors());
                }
                getNodeJob(depCell, depTarget);
              } catch (BuildTargetException e) {
                // No biggie, we'll hit the error again in the non-speculative path.
                LOG.info(e, "Could not schedule speculative parsing for %s", depTarget);
              }
            }
          });
    }
    return targetNode;
  }

  @Override
  protected ListenableFuture<ImmutableList<RawTargetNode>> getItemsToConvert(
      Cell cell, Path buildFile) throws BuildTargetException {
    return rawTargetNodePipeline.getAllNodesJob(cell, buildFile, EmptyTargetConfiguration.INSTANCE);
  }

  @Override
  protected ListenableFuture<RawTargetNode> getItemToConvert(Cell cell, BuildTarget buildTarget)
      throws BuildTargetException {
    return rawTargetNodePipeline.getNodeJob(cell, buildTarget);
  }
}
