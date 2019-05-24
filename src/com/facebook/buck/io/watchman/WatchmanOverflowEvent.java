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
package com.facebook.buck.io.watchman;

import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Buck sends this event when Watchman is unable to correctly determine the whole set of changes in
 * the filesystem, or if too many files have changed
 */
@Value.Immutable(copy = false, builder = false)
public abstract class WatchmanOverflowEvent implements WatchmanEvent {
  @Override
  @Value.Parameter
  public abstract Path getCellPath();

  /** Human-readable message why overflow event is sent */
  @Value.Parameter
  public abstract String getReason();
}
