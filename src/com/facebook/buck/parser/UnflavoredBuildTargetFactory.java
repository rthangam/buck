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
package com.facebook.buck.parser;

import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTargetView;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.base.Joiner;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class UnflavoredBuildTargetFactory {

  private UnflavoredBuildTargetFactory() {}

  /**
   * @param cellRoot Absolute path to the root of the cell the rule is defined in.
   * @param map the map of values that define the rule.
   * @param buildFilePath Absolute path to the build file the rule is defined in
   * @return the build target defined by the rule.
   */
  public static UnflavoredBuildTargetView createFromRawNode(
      Path cellRoot, Optional<String> cellName, Map<String, Object> map, Path buildFilePath) {
    @Nullable String basePath = (String) map.get(InternalTargetAttributeNames.BASE_PATH);
    @Nullable String name = (String) map.get("name");
    if (basePath == null || name == null) {
      throw new IllegalStateException(
          String.format(
              "Attempting to parse build target from malformed raw data in %s: %s.",
              buildFilePath, Joiner.on(",").withKeyValueSeparator("->").join(map)));
    }
    Path otherBasePath = cellRoot.relativize(MorePaths.getParentOrEmpty(buildFilePath));
    if (!otherBasePath.equals(otherBasePath.getFileSystem().getPath(basePath))) {
      throw new IllegalStateException(
          String.format(
              "Raw data claims to come from [%s], but we tried rooting it at [%s].",
              basePath, otherBasePath));
    }
    return ImmutableUnflavoredBuildTargetView.of(
        cellRoot, cellName, UnflavoredBuildTargetView.BUILD_TARGET_PREFIX + basePath, name);
  }
}
