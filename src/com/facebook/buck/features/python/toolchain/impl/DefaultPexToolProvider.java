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

package com.facebook.buck.features.python.toolchain.impl;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class DefaultPexToolProvider implements PexToolProvider {

  private static final Path DEFAULT_PATH_TO_PEX = getPathToPex().toAbsolutePath();

  private static Path getPathToPex() {
    String moduleResourceLocation = System.getProperty("buck.module.resources");
    if (moduleResourceLocation == null) {
      return Paths.get("src", "com", "facebook", "buck", "features", "python", "make_pex.py");
    } else {
      if ("repository".equals(System.getProperty("buck.mode"))) {
        return Paths.get(moduleResourceLocation).resolve("python").resolve("make_pex.py");
      } else {
        return Paths.get(moduleResourceLocation).resolve("python").resolve("pex.pex");
      }
    }
  }

  private final ToolchainProvider toolchainProvider;
  private final PythonBuckConfig pythonBuckConfig;
  private final RuleKeyConfiguration ruleKeyConfiguration;

  public DefaultPexToolProvider(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      RuleKeyConfiguration ruleKeyConfiguration) {
    this.toolchainProvider = toolchainProvider;
    this.pythonBuckConfig = pythonBuckConfig;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
  }

  @Override
  public Tool getPexTool(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    CommandTool.Builder builder =
        new CommandTool.Builder(getRawPexTool(resolver, ruleKeyConfiguration, targetConfiguration));
    for (String flag : Splitter.on(' ').omitEmptyStrings().split(pythonBuckConfig.getPexFlags())) {
      builder.addArg(flag);
    }

    return builder.build();
  }

  private Tool getRawPexTool(
      BuildRuleResolver resolver,
      RuleKeyConfiguration ruleKeyConfiguration,
      TargetConfiguration targetConfiguration) {
    Optional<Tool> executable = pythonBuckConfig.getRawPexTool(resolver, targetConfiguration);
    if (executable.isPresent()) {
      return executable.get();
    }

    PythonInterpreter pythonInterpreter =
        toolchainProvider.getByName(PythonInterpreter.DEFAULT_NAME, PythonInterpreter.class);

    return VersionedTool.builder()
        .setName("pex")
        .setVersion(ruleKeyConfiguration.getCoreKey())
        .setPath(pythonBuckConfig.getSourcePath(pythonInterpreter.getPythonInterpreterPath()))
        .addExtraArgs(DEFAULT_PATH_TO_PEX.toString())
        .build();
  }
}
