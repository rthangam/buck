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

package com.facebook.buck.remoteexecution.util;

import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionHandle;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionResult;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Action;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Command;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.facebook.buck.util.Scope;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** IsolatedExecution implementation that will run buildrules in a subprocess. */
public class OutOfProcessIsolatedExecutionClients implements RemoteExecutionClients {
  private final Protocol protocol;
  private final NamedTemporaryDirectory workDir;
  private final LocalContentAddressedStorage storage;
  private final RemoteExecutionServiceClient executionService;

  /**
   * Returns a RemoteExecution implementation that uses a local CAS and a separate local temporary
   * directory for execution.
   */
  public static OutOfProcessIsolatedExecutionClients create(
      Protocol protocol, BuckEventBus eventBus) throws IOException {
    return new OutOfProcessIsolatedExecutionClients(protocol, eventBus);
  }

  private OutOfProcessIsolatedExecutionClients(final Protocol protocol, BuckEventBus eventBus)
      throws IOException {
    this.workDir = new NamedTemporaryDirectory("__work__");
    this.storage =
        new LocalContentAddressedStorage(workDir.getPath().resolve("__cache__"), protocol);
    this.protocol = protocol;
    this.executionService =
        (actionDigest, ruleName, metadataProvider) -> {
          Action action = storage.materializeAction(actionDigest);

          Path buildDir = workDir.getPath().resolve(action.getInputRootDigest().getHash());
          try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
            Command command;
            try (Scope ignored2 = LeafEvents.scope(eventBus, "materializing_inputs")) {
              command =
                  storage
                      .materializeInputs(
                          buildDir,
                          action.getInputRootDigest(),
                          Optional.of(action.getCommandDigest()))
                      .get();
            }

            ActionRunner.ActionResult actionResult =
                new ActionRunner(protocol, eventBus)
                    .runAction(
                        command.getCommand(),
                        command.getEnvironment(),
                        command.getOutputDirectories().stream()
                            .map(Paths::get)
                            .collect(ImmutableSet.toImmutableSet()),
                        buildDir);
            try (Scope ignored2 = LeafEvents.scope(eventBus, "uploading_results")) {
              Futures.getUnchecked(storage.addMissing(actionResult.requiredData));
            }
            ListenableFuture<ExecutionResult> executionResult =
                Futures.immediateFuture(
                    new ExecutionResult() {
                      @Override
                      public ImmutableList<OutputDirectory> getOutputDirectories() {
                        return actionResult.outputDirectories;
                      }

                      @Override
                      public ImmutableList<OutputFile> getOutputFiles() {
                        return actionResult.outputFiles;
                      }

                      @Override
                      public int getExitCode() {
                        return actionResult.exitCode;
                      }

                      @Override
                      public Optional<String> getStdout() {
                        return Optional.of(actionResult.stdout);
                      }

                      @Override
                      public Optional<String> getStderr() {
                        return Optional.of(actionResult.stderr);
                      }

                      @Override
                      public Digest getActionResultDigest() {
                        return protocol.newDigest("", 0);
                      }

                      @Override
                      public ExecutedActionMetadata getActionMetadata() {
                        return ExecutedActionMetadata.getDefaultInstance();
                      }
                    });
            return new ExecutionHandle() {
              @Override
              public ListenableFuture<ExecutionResult> getResult() {
                return executionResult;
              }

              @Override
              public ListenableFuture<ExecuteOperationMetadata> getExecutionStarted() {
                return SettableFuture.create();
              }

              @Override
              public void cancel() {}
            };
          }
        };
  }

  @Override
  public RemoteExecutionServiceClient getRemoteExecutionService() {
    return executionService;
  }

  @Override
  public ContentAddressedStorageClient getContentAddressedStorage() {
    return storage;
  }

  @Override
  public Protocol getProtocol() {
    return protocol;
  }

  @Override
  public void close() throws IOException {
    workDir.close();
  }
}
