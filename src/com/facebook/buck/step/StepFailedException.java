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

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.ExceptionWithContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.WrapsException;
import java.util.Optional;

public class StepFailedException extends Exception implements WrapsException, ExceptionWithContext {
  private final Step step;
  private final String description;

  /** Callers should use {@link #createForFailingStepWithExitCode} unless in a unit test. */
  private StepFailedException(Throwable cause, Step step, String description) {
    super(cause);
    this.step = step;
    this.description = description;
  }

  @Override
  public String getMessage() {
    return getCause().getMessage() + System.lineSeparator() + "  " + getContext().get();
  }

  /** Creates a StepFailedException based on a StepExecutionResult. */
  public static StepFailedException createForFailingStepWithExitCode(
      Step step, ExecutionContext context, StepExecutionResult executionResult) {
    int exitCode = executionResult.getExitCode();
    StringBuilder messageBuilder = new StringBuilder();
    messageBuilder.append(String.format("Command failed with exit code %d.", exitCode));
    executionResult
        .getStderr()
        .ifPresent(
            stderr ->
                messageBuilder.append(System.lineSeparator()).append("stderr: ").append(stderr));
    return createForFailingStepWithException(
        step, context, new HumanReadableException(messageBuilder.toString()));
  }

  static StepFailedException createForFailingStepWithException(
      Step step, ExecutionContext context, Throwable throwable) {
    return new StepFailedException(throwable, step, descriptionForStep(step, context));
  }

  private static String descriptionForStep(Step step, ExecutionContext context) {
    return context.getVerbosity().shouldPrintCommand()
        ? step.getDescription(context)
        : step.getShortName();
  }

  public Step getStep() {
    return step;
  }

  @Override
  public Optional<String> getContext() {
    return Optional.of(String.format("When running <%s>.", description));
  }
}
