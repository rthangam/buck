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

package com.facebook.buck.core.cell.impl;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Supplier;
import org.pf4j.PluginManager;

/**
 * Creates a root cell, i.e. a cell that is representing the current repository.
 *
 * <p>The root cell is different from other cells: it doesn't require a path to the repository
 * directory since the root of the provided filesystem is considered to be the root of the cell. Its
 * name is also empty.
 */
public class RootCellFactory {

  public static Cell create(
      CellProvider cellProvider,
      CellPathResolver rootCellCellPathResolver,
      CellPathResolver rootCellPathResolver,
      ProjectFilesystem rootFilesystem,
      BuckModuleManager moduleManager,
      PluginManager pluginManager,
      BuckConfig rootConfig,
      ImmutableMap<String, String> environment,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      Supplier<TargetConfiguration> targetConfiguration) {
    Preconditions.checkState(
        !rootCellCellPathResolver.getCanonicalCellName(rootFilesystem.getRootPath()).isPresent(),
        "Root cell should be nameless");
    RuleKeyConfiguration ruleKeyConfiguration =
        ConfigRuleKeyConfigurationFactory.create(rootConfig, moduleManager);
    ToolchainProvider toolchainProvider =
        new DefaultToolchainProvider(
            pluginManager,
            environment,
            rootConfig,
            rootFilesystem,
            processExecutor,
            executableFinder,
            ruleKeyConfiguration,
            targetConfiguration);
    return ImmutableCell.of(
        rootCellCellPathResolver.getKnownRoots(),
        Optional.empty(),
        rootFilesystem,
        rootConfig,
        cellProvider,
        toolchainProvider,
        rootCellPathResolver);
  }

  static Cell create(
      CellProvider cellProvider,
      CellPathResolver rootCellCellPathResolver,
      ToolchainProviderFactory toolchainProviderFactory,
      ProjectFilesystem rootFilesystem,
      BuckModuleManager moduleManager,
      BuckConfig rootConfig) {
    Preconditions.checkState(
        !rootCellCellPathResolver.getCanonicalCellName(rootFilesystem.getRootPath()).isPresent(),
        "Root cell should be nameless");
    RuleKeyConfiguration ruleKeyConfiguration =
        ConfigRuleKeyConfigurationFactory.create(rootConfig, moduleManager);
    ToolchainProvider toolchainProvider =
        toolchainProviderFactory.create(rootConfig, rootFilesystem, ruleKeyConfiguration);
    return ImmutableCell.of(
        rootCellCellPathResolver.getKnownRoots(),
        Optional.empty(),
        rootFilesystem,
        rootConfig,
        cellProvider,
        toolchainProvider,
        rootCellCellPathResolver);
  }
}
