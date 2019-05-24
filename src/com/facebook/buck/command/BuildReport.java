/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.command;

import static com.facebook.buck.util.string.MoreStrings.linesToText;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.LogImpl;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

@VisibleForTesting
public class BuildReport {
  private static final Logger LOG = Logger.get(BuildReport.class);

  private final BuildExecutionResult buildExecutionResult;
  private final SourcePathResolver pathResolver;
  private final Cell rootCell;

  /**
   * @param buildExecutionResult the build result to generate the report for.
   * @param pathResolver source path resolver which can be used for the result.
   */
  public BuildReport(
      BuildExecutionResult buildExecutionResult, SourcePathResolver pathResolver, Cell rootCell) {
    this.buildExecutionResult = buildExecutionResult;
    this.pathResolver = pathResolver;
    this.rootCell = rootCell;
  }

  public String generateForConsole(Console console) {
    Ansi ansi = console.getAnsi();
    Map<BuildRule, Optional<BuildResult>> ruleToResult = buildExecutionResult.getResults();

    StringBuilder report = new StringBuilder();
    for (Map.Entry<BuildRule, Optional<BuildResult>> entry : ruleToResult.entrySet()) {
      BuildRule rule = entry.getKey();
      Optional<BuildRuleSuccessType> success = Optional.empty();
      Optional<BuildResult> result = entry.getValue();
      if (result.isPresent()) {
        success = result.get().getSuccessOptional();
      }

      String successIndicator;
      String successType;
      Path outputPath;
      if (success.isPresent()) {
        successIndicator = ansi.asHighlightedSuccessText("OK  ");
        successType = success.get().name();
        outputPath = getRuleOutputPath(rule);
      } else {
        successIndicator = ansi.asHighlightedFailureText("FAIL");
        successType = null;
        outputPath = null;
      }

      report.append(
          String.format(
              "%s %s%s%s",
              successIndicator,
              rule.getBuildTarget(),
              successType != null ? " " + successType : "",
              outputPath != null ? " " + outputPath.toString() : ""));
      report.append(System.lineSeparator());
    }

    if (!buildExecutionResult.getFailures().isEmpty()
        && console.getVerbosity().shouldPrintStandardInformation()) {
      report.append(linesToText("", " ** Summary of failures encountered during the build **", ""));
      for (BuildResult failureResult : buildExecutionResult.getFailures()) {
        Throwable failure = Objects.requireNonNull(failureResult.getFailure());
        new ErrorLogger(
                new ErrorLogger.LogImpl() {
                  @Override
                  public void logUserVisible(String message) {
                    report.append(
                        String.format(
                            "Rule %s FAILED because %s.",
                            failureResult.getRule().getFullyQualifiedName(), message));
                  }

                  @Override
                  public void logUserVisibleInternalError(String message) {
                    logUserVisible(message);
                  }

                  @Override
                  public void logVerbose(Throwable e) {
                    LOG.debug(
                        e,
                        "Error encountered while building %s.",
                        failureResult.getRule().getFullyQualifiedName());
                  }
                },
                new HumanReadableExceptionAugmentor(ImmutableMap.of()))
            .setSuppressStackTraces(true)
            .logException(failure);
      }
    }

    return report.toString();
  }

  public String generateJsonBuildReport() throws IOException {
    Map<BuildRule, Optional<BuildResult>> ruleToResult = buildExecutionResult.getResults();
    LinkedHashMap<String, Object> results = new LinkedHashMap<>();
    LinkedHashMap<String, Object> failures = new LinkedHashMap<>();
    boolean isOverallSuccess = true;
    for (Map.Entry<BuildRule, Optional<BuildResult>> entry : ruleToResult.entrySet()) {
      BuildRule rule = entry.getKey();
      Optional<BuildRuleSuccessType> success = Optional.empty();
      Optional<BuildResult> result = entry.getValue();
      if (result.isPresent()) {
        success = result.get().getSuccessOptional();
      }
      Map<String, Object> value = new LinkedHashMap<>();

      boolean isSuccess = success.isPresent();
      value.put("success", isSuccess);
      if (!isSuccess) {
        isOverallSuccess = false;
      }

      if (isSuccess) {
        value.put("type", success.get().name());
        Path outputPath = getRuleOutputPath(rule);
        value.put("output", outputPath != null ? outputPath.toString() : null);
      }
      results.put(rule.getFullyQualifiedName(), value);
    }

    for (BuildResult failureResult : buildExecutionResult.getFailures()) {
      Throwable failure = Objects.requireNonNull(failureResult.getFailure());
      StringBuilder messageBuilder = new StringBuilder();
      new ErrorLogger(
              new LogImpl() {
                @Override
                public void logUserVisible(String message) {
                  messageBuilder.append(message);
                }

                @Override
                public void logUserVisibleInternalError(String message) {
                  messageBuilder.append(message);
                }

                @Override
                public void logVerbose(Throwable e) {}
              },
              new HumanReadableExceptionAugmentor(ImmutableMap.of()))
          .setSuppressStackTraces(true)
          .logException(failure);
      failures.put(failureResult.getRule().getFullyQualifiedName(), messageBuilder.toString());
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("success", isOverallSuccess);
    report.put("results", results);
    report.put("failures", failures);
    return ObjectMappers.WRITER
        .withFeatures(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(report);
  }

  private @Nullable Path getRuleOutputPath(BuildRule rule) {
    SourcePath outputFile = rule.getSourcePathToOutput();
    if (outputFile == null) {
      return null;
    }

    Path relativeOutputPath = pathResolver.getRelativePath(outputFile);
    Path absoluteOutputPath = rule.getProjectFilesystem().resolve(relativeOutputPath);
    return rootCell.getFilesystem().relativize(absoluteOutputPath);
  }
}
