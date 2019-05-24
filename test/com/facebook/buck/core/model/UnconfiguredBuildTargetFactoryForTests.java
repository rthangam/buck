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

package com.facebook.buck.core.model;

import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTargetView;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.support.cli.args.BuckCellArg;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/** Allows to create {@link UnconfiguredBuildTargetView} during testing. */
public class UnconfiguredBuildTargetFactoryForTests {

  private UnconfiguredBuildTargetFactoryForTests() {}

  public static UnconfiguredBuildTargetView newInstance(
      ProjectFilesystem projectFilesystem, String fullyQualifiedName) {
    return newInstance(projectFilesystem.getRootPath(), fullyQualifiedName);
  }

  public static UnconfiguredBuildTargetView newInstance(String fullyQualifiedName) {
    return newInstance((Path) null, fullyQualifiedName);
  }

  public static UnconfiguredBuildTargetView newInstance(
      @Nullable Path root, String fullyQualifiedName) {
    root = root == null ? new FakeProjectFilesystem().getRootPath() : root;

    BuckCellArg arg = BuckCellArg.of(fullyQualifiedName);
    Optional<String> cellName = arg.getCellName();
    String[] parts = arg.getBasePath().split(":");
    Preconditions.checkArgument(parts.length == 2);
    String[] nameAndFlavor = parts[1].split("#");
    if (nameAndFlavor.length != 2) {
      return ImmutableUnconfiguredBuildTargetView.of(
          ImmutableUnflavoredBuildTargetView.of(root, cellName, parts[0], parts[1]));
    }
    String[] flavors = nameAndFlavor[1].split(",");
    return ImmutableUnconfiguredBuildTargetView.of(
        ImmutableUnflavoredBuildTargetView.of(root, cellName, parts[0], nameAndFlavor[0]),
        RichStream.from(flavors).map(InternalFlavor::of));
  }

  public static UnconfiguredBuildTargetView newInstance(
      Path cellPath, String baseName, String shortName) {
    BuckCellArg arg = BuckCellArg.of(baseName);
    return ImmutableUnconfiguredBuildTargetView.of(
        ImmutableUnflavoredBuildTargetView.of(
            cellPath, arg.getCellName(), arg.getBasePath(), shortName));
  }

  public static UnconfiguredBuildTargetView newInstance(
      Path cellPath, String baseName, String shortName, Flavor... flavors) {
    BuckCellArg arg = BuckCellArg.of(baseName);
    return ImmutableUnconfiguredBuildTargetView.of(
        ImmutableUnflavoredBuildTargetView.of(
            cellPath, arg.getCellName(), arg.getBasePath(), shortName),
        RichStream.from(flavors));
  }
}
