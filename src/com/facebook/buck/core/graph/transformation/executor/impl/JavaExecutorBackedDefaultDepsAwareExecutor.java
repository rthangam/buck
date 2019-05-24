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

package com.facebook.buck.core.graph.transformation.executor.impl;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask.DepsSupplier;
import com.facebook.buck.core.graph.transformation.executor.impl.AbstractDepsAwareTask.TaskStatus;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A specialized Executor that executes {@link DepsAwareTask}. This executor will attempt to
 * maintain maximum concurrency, while completing dependencies of each supplied work first.
 *
 * <p>This implementation submits all tasks to java's default executors in a queue, without
 * attempting insert dependent work in the front of the queue, but rather continuously requeue-ing
 * them until an executable work is found. This is how Bazel's skyframe works.
 */
public class JavaExecutorBackedDefaultDepsAwareExecutor<T>
    implements DepsAwareExecutor<T, DefaultDepsAwareTask<T>> {

  private final ExecutorService executorService;

  private volatile boolean isShutdown = false;

  private JavaExecutorBackedDefaultDepsAwareExecutor(ExecutorService executorService) {
    this.executorService = executorService;
  }

  /**
   * Creates a {@link JavaExecutorBackedDefaultDepsAwareExecutor} with given {@code
   * numberOfThreads}.
   */
  public static <U> JavaExecutorBackedDefaultDepsAwareExecutor<U> of(int numberOfThreads) {
    ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
    return new JavaExecutorBackedDefaultDepsAwareExecutor<>(executorService);
  }

  @Override
  public void close() {
    isShutdown = true;
    executorService.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return isShutdown;
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(
      Callable<T> callable, Supplier<ImmutableSet<DefaultDepsAwareTask<T>>> depsSupplier) {
    return DefaultDepsAwareTask.of(
        callable, DepsSupplier.of(ThrowingSupplier.fromSupplier(depsSupplier)));
  }

  @Override
  public DefaultDepsAwareTask<T> createThrowingTask(
      Callable<T> callable,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> prereqSupplier,
      ThrowingSupplier<ImmutableSet<DefaultDepsAwareTask<T>>, Exception> depsSupplier) {
    return DefaultDepsAwareTask.of(callable, DepsSupplier.of(prereqSupplier, depsSupplier));
  }

  @Override
  public DefaultDepsAwareTask<T> createTask(Callable<T> callable) {
    return DefaultDepsAwareTask.of(callable);
  }

  @Override
  public Future<T> submit(DefaultDepsAwareTask<T> task) {
    if (isShutdown) {
      throw new RejectedExecutionException("Executor has already been shutdown");
    }
    submitTask(task);
    return task.getResultFuture();
  }

  @Override
  public ImmutableList<Future<T>> submitAll(Collection<DefaultDepsAwareTask<T>> tasks) {
    ImmutableList.Builder<Future<T>> futures = ImmutableList.builderWithExpectedSize(tasks.size());
    for (DefaultDepsAwareTask<T> w : tasks) {
      futures.add(submit(w));
    }
    return futures.build();
  }

  private void submitTask(DefaultDepsAwareTask<?> task) {
    if (!task.compareAndSetStatus(TaskStatus.NOT_SCHEDULED, TaskStatus.SCHEDULED)) {
      return;
    }
    executorService.submit(
        () -> {
          runTask(task);
          return null;
        });
  }

  private <U> boolean checkTasksReadyOrSchedule(
      ImmutableSet<DefaultDepsAwareTask<U>> tasksToCheck) {
    boolean ret = true;
    for (DefaultDepsAwareTask<?> task : tasksToCheck) {
      if (task.getStatus() != TaskStatus.DONE) {
        ret = false;
        if (task.getStatus() == TaskStatus.NOT_SCHEDULED) {
          submitTask(task);
        }
      }
    }
    return ret;
  }

  private <U> void runTask(DefaultDepsAwareTask<U> task) throws Exception {

    /**
     * This executor uses the {@link TaskStatus#SCHEDULED} to indicate that the task has been added
     * to the queue of the backing executors. {@link TaskStatus#DONE} should be set for when the
     * task has completed to prevent the task from being reran again.
     */
    Verify.verify(task.compareAndSetStatus(TaskStatus.SCHEDULED, TaskStatus.STARTED));
    if (!checkTasksReadyOrSchedule(task.getPrereqs())) {
      Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.NOT_SCHEDULED));
      submitTask(task);
      return;
    }
    if (!checkTasksReadyOrSchedule(task.getDependencies())) {
      Verify.verify(task.compareAndSetStatus(TaskStatus.STARTED, TaskStatus.NOT_SCHEDULED));
      submitTask(task);
      return;
    }

    task.call();
  }
}
