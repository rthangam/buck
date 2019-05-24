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

package com.facebook.buck.core.toolchain.toolprovider.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

/**
 * A {@link ToolProvider} which returns a {@link HashedFileTool} found from searching the system.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSystemToolProvider implements ToolProvider {
  abstract ExecutableFinder getExecutableFinder();

  abstract ImmutableMap<String, String> getEnvironment();

  abstract Function<Path, SourcePath> getSourcePathConverter();

  abstract Path getName();

  abstract Optional<String> getSource();

  @Value.Lazy
  public Tool resolve() {
    return getExecutableFinder()
        .getOptionalExecutable(getName(), getEnvironment())
        .map(getSourcePathConverter())
        .map(HashedFileTool::new)
        .orElseThrow(
            () -> {
              StringBuilder msg = new StringBuilder();
              msg.append(String.format("Cannot find system executable \"%s\"", getName()));
              getSource().ifPresent(source -> msg.append("from ").append(source));
              return new HumanReadableException(msg.toString());
            });
  }

  @Override
  public Tool resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return resolve();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.of();
  }
}
