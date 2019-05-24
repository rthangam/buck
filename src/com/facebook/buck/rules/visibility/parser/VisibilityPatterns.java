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

package com.facebook.buck.rules.visibility.parser;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import javax.annotation.Nullable;

/** Provides additional ways to create {@link VisibilityPattern}s. */
public class VisibilityPatterns {

  /** Prevent instantiations. */
  private VisibilityPatterns() {}

  @SuppressWarnings("unchecked")
  public static ImmutableSet<VisibilityPattern> createFromStringList(
      CellPathResolver cellNames,
      String paramName,
      @Nullable Object value,
      UnconfiguredBuildTarget target) {
    if (value == null) {
      return ImmutableSet.of();
    }
    if (!(value instanceof List)) {
      throw new RuntimeException(
          String.format("Expected an array for %s but was %s", paramName, value));
    }
    List<String> originalPatterns = (List<String>) value;
    ImmutableSet.Builder<VisibilityPattern> patterns =
        ImmutableSet.builderWithExpectedSize(originalPatterns.size());
    for (String visibility : originalPatterns) {
      try {
        patterns.add(VisibilityPatternParser.parse(cellNames, visibility));
      } catch (IllegalArgumentException e) {
        throw new HumanReadableException(
            e,
            "Bad visibility expression: %s listed %s in its %s argument, but only %s "
                + "or fully qualified target patterns are allowed (i.e. those starting with "
                + "// or a cell).",
            target.getFullyQualifiedName(),
            visibility,
            paramName,
            VisibilityPatternParser.VISIBILITY_PUBLIC);
      }
    }
    return patterns.build();
  }
}
