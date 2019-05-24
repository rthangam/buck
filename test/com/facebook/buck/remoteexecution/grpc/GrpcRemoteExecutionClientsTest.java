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

package com.facebook.buck.remoteexecution.grpc;

import static org.junit.Assert.assertEquals;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionImplBase;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionHandle;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.remoteexecution.util.OutputsMaterializer.FilesystemFileMaterializer;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GrpcRemoteExecutionClientsTest {
  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private List<BindableService> services = new ArrayList<>();
  private RemoteExecutionClients clients;

  public void setupServer() throws IOException {
    clients = new TestRemoteExecutionClients(services);
  }

  @After
  public void tearDown() throws Exception {
    clients.close();
  }

  @Test
  public void testExecute() throws Exception {
    String stdout = "stdout";
    String stderr = "stderr";

    services.add(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            ActionResult.Builder grpcActionResultBuilder = ActionResult.newBuilder();
            grpcActionResultBuilder
                .setExitCode(0)
                .setStdoutRaw(ByteString.copyFromUtf8(stdout))
                .setStderrRaw(ByteString.copyFromUtf8(stderr));

            responseObserver.onNext(
                Operation.newBuilder()
                    .setDone(true)
                    .setResponse(
                        Any.pack(
                            ExecuteResponse.newBuilder()
                                .setResult(grpcActionResultBuilder)
                                .setStatus(
                                    com.google.rpc.Status.newBuilder().setCode(Code.OK.value()))
                                .setCachedResult(false)
                                .build()))
                    .build());

            responseObserver.onCompleted();
          }
        });

    setupServer();

    ExecutionResult executionResult =
        clients
            .getRemoteExecutionService()
            .execute(
                clients.getProtocol().computeDigest("".getBytes(Charsets.UTF_8)),
                "",
                MetadataProviderFactory.emptyMetadataProvider())
            .getResult()
            .get();

    assertEquals(0, executionResult.getExitCode());
    assertEquals(stderr, executionResult.getStderr().get());
  }

  @Test
  public void testExecuteCancel() throws Exception {
    AtomicReference<StreamObserver<Operation>> responseObserverCapture = new AtomicReference<>();

    services.add(
        new ExecutionImplBase() {
          @Override
          public void execute(ExecuteRequest request, StreamObserver<Operation> responseObserver) {
            responseObserverCapture.set(responseObserver);
          }
        });

    setupServer();

    ExecutionHandle executionResult =
        clients
            .getRemoteExecutionService()
            .execute(
                clients.getProtocol().computeDigest("".getBytes(Charsets.UTF_8)),
                "",
                MetadataProviderFactory.emptyMetadataProvider());

    responseObserverCapture.get().onNext(Operation.newBuilder().setDone(false).build());

    executionResult.cancel();

    // Check that the server is notified of cancellation.
    expectedException.expect(StatusRuntimeException.class);
    responseObserverCapture.get().onNext(Operation.newBuilder().setDone(false).build());
  }

  @Test
  public void testStorage() throws Exception {
    Protocol protocol = new GrpcProtocol();
    Path root = temporaryPaths.getRoot();
    Path cacheDir = root.resolve("cache");
    Files.createDirectories(cacheDir);
    Path workDir = root.resolve("work");
    Files.createDirectories(workDir);
    LocalContentAddressedStorage storage =
        new LocalContentAddressedStorage(cacheDir, new GrpcProtocol());
    services.add(new LocalBackedCasServer(storage));
    services.add(new LocalBackedByteStreamServer(storage));

    setupServer();

    List<UploadDataSupplier> requiredData = new ArrayList<>();

    String data1 = "data1";
    Digest digest1 = protocol.computeDigest(data1.getBytes(Charsets.UTF_8));
    requiredData.add(
        UploadDataSupplier.of(
            digest1, () -> new ByteArrayInputStream(data1.getBytes(Charsets.UTF_8))));

    String data2 = "data2";
    Digest digest2 = protocol.computeDigest(data2.getBytes(Charsets.UTF_8));
    requiredData.add(
        UploadDataSupplier.of(
            digest2, () -> new ByteArrayInputStream(data2.getBytes(Charsets.UTF_8))));

    clients.getContentAddressedStorage().addMissing(requiredData).get();

    clients
        .getContentAddressedStorage()
        .materializeOutputs(
            ImmutableList.of(), ImmutableList.of(), new FilesystemFileMaterializer(workDir))
        .get();

    assertEquals(ImmutableMap.of(), getDirectoryContents(workDir));

    Path out1 = Paths.get("out1");
    Path out2 = Paths.get("out2");
    clients
        .getContentAddressedStorage()
        .materializeOutputs(
            ImmutableList.of(),
            ImmutableList.of(
                protocol.newOutputFile(out1, digest1, false),
                protocol.newOutputFile(out2, digest2, false)),
            new FilesystemFileMaterializer(workDir))
        .get();

    assertEquals(ImmutableMap.of(out1, data1, out2, data2), getDirectoryContents(workDir));
  }

  private ImmutableMap<Path, String> getDirectoryContents(Path workDir) throws IOException {
    Builder<Path, String> contentsBuilder = ImmutableMap.builder();
    try (Stream<Path> stream = Files.list(workDir)) {
      for (Path path : (Iterable<Path>) stream::iterator) {
        contentsBuilder.put(
            workDir.relativize(path), new String(Files.readAllBytes(path), Charsets.UTF_8));
      }
    }
    return contentsBuilder.build();
  }

  // TODO(cjhopman): Add test for hanging execution.
}
