/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Path;

public class MoveStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path source;
  private final Path destination;
  private final CopyOption[] options;

  public MoveStep(
      ProjectFilesystem filesystem, Path source, Path destination, CopyOption... options) {
    this.filesystem = filesystem;
    this.source = source;
    this.destination = destination;
    this.options = options;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    filesystem.move(source, destination, options);
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "mv";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("mv %s %s", source, destination);
  }
}
