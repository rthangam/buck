/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.doctor.config;

import com.facebook.buck.core.model.BuildId;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.immutables.value.Value;

@Value.Immutable(copy = false, builder = false)
public interface BuildLogEntry {

  @Value.Parameter
  Path getRelativePath();

  @Value.Parameter
  Optional<BuildId> getBuildId();

  @Value.Parameter
  Optional<List<String>> getCommandArgs();

  @Value.Parameter
  OptionalInt getExitCode();

  @Value.Parameter
  OptionalInt getBuildTimeMs();

  @Value.Parameter
  Optional<Path> getRuleKeyLoggerLogFile();

  @Value.Parameter
  Optional<Path> getMachineReadableLogFile();

  @Value.Parameter
  Optional<Path> getRuleKeyDiagKeysFile();

  @Value.Parameter
  Optional<Path> getRuleKeyDiagGraphFile();

  @Value.Parameter
  Optional<Path> getTraceFile();

  @Value.Parameter
  long getSize();

  @Value.Parameter
  Date getLastModifiedTime();

  @Value.Check
  default void pathIsRelative() {
    Preconditions.checkState(!getRelativePath().isAbsolute());
    if (getRuleKeyLoggerLogFile().isPresent()) {
      Preconditions.checkState(!getRuleKeyLoggerLogFile().get().isAbsolute());
    }
    if (getTraceFile().isPresent()) {
      Preconditions.checkState(!getTraceFile().get().isAbsolute());
    }
  }
}
