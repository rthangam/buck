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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.parser.BuildTargetMatcherTargetNodeParser;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.support.cli.args.BuckCellArg;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A helper wrapper over {@link BuildTargetMatcherTargetNodeParser} to normalize user input before
 * parsing, resolve aliases and validate that base path exists
 */
public class CommandLineTargetNodeSpecParser {

  private final BuckConfig config;
  private final BuildTargetMatcherTargetNodeParser parser;

  public CommandLineTargetNodeSpecParser(
      BuckConfig config, BuildTargetMatcherTargetNodeParser parser) {
    this.config = config;
    this.parser = parser;
  }

  @VisibleForTesting
  protected String normalizeBuildTargetString(String target) {
    // Check and save the cell name
    BuckCellArg arg = BuckCellArg.of(target);
    target = arg.getArg();

    // Look up the section after the colon, if present, and strip it off.
    int colonIndex = target.indexOf(':');
    Optional<String> nameAfterColon = Optional.empty();
    if (colonIndex != -1) {
      nameAfterColon = Optional.of(target.substring(colonIndex + 1));
      target = target.substring(0, colonIndex);
    }

    // Strip trailing slashes in the directory name part.
    while (target.endsWith("/")) {
      target = target.substring(0, target.length() - 1);
    }

    // If no colon was specified and we're not dealing with a trailing "...", we'll add in the
    // missing colon and fill in the missing rule name with the basename of the directory.
    if (!nameAfterColon.isPresent() && !target.endsWith("/...") && !target.equals("...")) {
      int lastSlashIndex = target.lastIndexOf('/');
      if (lastSlashIndex == -1) {
        nameAfterColon = Optional.of(target);
      } else {
        nameAfterColon = Optional.of(target.substring(lastSlashIndex + 1));
      }
    }

    // Now add in the name after the colon if there was one.
    if (nameAfterColon.isPresent()) {
      target += ":" + nameAfterColon.get();
    }

    return arg.getCellName().orElse("") + "//" + target;
  }

  /**
   * Validates a {@code spec} and throws an exception for invalid ones.
   *
   * <p>Ideally validation should happen as part of spec creation and some of them actually happen,
   * but others, especially those that require filesystem interactions, are too expensive to carry
   * for every single build target.
   */
  private void validateTargetSpec(TargetNodeSpec spec, String arg, Cell owningCell) {
    Path cellPath = spec.getBuildFileSpec().getCellPath();
    Path basePath = spec.getBuildFileSpec().getBasePath();
    Cell realCell = owningCell.getCell(cellPath);
    if (!realCell.getFilesystem().exists(basePath)) {
      throw new HumanReadableException("%s references non-existent directory %s", arg, basePath);
    }
  }

  /**
   * Parse command line argument provided by user into a set of {@link TargetNodeSpec}s
   *
   * @param owningCell Cell that owns the resolution of a spec
   * @param arg Unresolved command line argument, can be alias or target name or recursive spec
   */
  public ImmutableSet<TargetNodeSpec> parse(Cell owningCell, String arg) {
    ImmutableSet<String> resolvedArgs =
        AliasConfig.from(config).getBuildTargetForAliasAsString(arg);
    if (resolvedArgs.isEmpty()) {
      resolvedArgs = ImmutableSet.of(arg);
    }
    ImmutableSet.Builder<TargetNodeSpec> specs = new ImmutableSet.Builder<>();
    for (String resolvedArg : resolvedArgs) {
      String buildTarget = normalizeBuildTargetString(resolvedArg);
      TargetNodeSpec spec = parser.parse(owningCell.getCellPathResolver(), buildTarget);
      validateTargetSpec(spec, resolvedArg, owningCell);
      specs.add(spec);
    }
    return specs.build();
  }
}
