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

package com.facebook.buck.support.bgtasks;

import com.facebook.buck.core.model.BuildId;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * BackgroundTaskManager schedules and runs background tasks like cleanup and logging. A manager
 * should be notified when a new command starts and when it finishes so that it can schedule tasks
 * appropriately. Tasks should typically be scheduled through a {@link TaskManagerCommandScope}.
 */
public abstract class BackgroundTaskManager {

  /** Type of notification passed to {@link #notify}. */
  enum Notification {
    /** Indicates that a command has started */
    COMMAND_START,
    /**
     * Indicates that a command has finished. This notification may trigger execution of background
     * tasks.
     */
    COMMAND_END
  }

  /**
   * Returns a new {@link TaskManagerCommandScope} for a build on this manager. The {@link
   * TaskManagerCommandScope} lives for the duration of the command such that it's {@link
   * TaskManagerCommandScope#close()} will trigger the tasks scheduled to be ran.
   *
   * @param buildId unique identifier {@link BuildId} of a command that created a scope
   * @param blocking whether the current command should wait for tasks to finish on exit
   */
  public abstract TaskManagerCommandScope getNewScope(BuildId buildId, boolean blocking);

  /** Shut down manager, without waiting for tasks to finish. */
  public abstract void shutdownNow();

  /**
   * Shut down manager, waiting until given timeout for tasks to finish.
   *
   * @param timeout timeout for tasks to finish
   * @param units units of timeout
   */
  public abstract void shutdown(long timeout, TimeUnit units) throws InterruptedException;

  /**
   * Schedule a task to be run in the background. Should be accessed through a {@link
   * TaskManagerCommandScope} implementation.
   */
  abstract Future<Void> schedule(ManagedBackgroundTask<?> task);

  /**
   * Notify the manager of some event, e.g. command start or end. Exceptions should generally be
   * caught and handled by the manager, except in test implementations. {@link Notification} should
   * be handled through a {@link TaskManagerCommandScope}.
   */
  abstract void notify(Notification code);
}
