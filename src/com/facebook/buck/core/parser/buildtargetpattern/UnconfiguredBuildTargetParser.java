/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.parser.buildtargetpattern;

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;

/**
 * Factory that parses a string into {@link com.facebook.buck.core.model.UnconfiguredBuildTarget}.
 *
 * <p>Fully qualified build target name looks like `cell//path/to:target#flavor1,flavor2`, where
 * `cell` and `path/to` components is allowed to be empty, and flavors may not be specified along
 * with `#` sign. So a minimum valid build target string is `//:target` indicating a target named
 * `target` at the package on the root path of the default cell without flavors
 */
public class UnconfiguredBuildTargetParser {

  private UnconfiguredBuildTargetParser() {}

  /**
   * Parse a string representing fully qualified build target, validating build target format
   *
   * <p>Fully qualified build target format is `cell//path/to:target#flavor1,flavor2` where cell may
   * be an empty string, and flavors may be omitted along with `#` sign
   *
   * @param target String representing fully-qualified build target, for example "//foo/bar:bar"
   * @throws BuildTargetParseException If build target format is invalid; at this moment {@link
   *     BuildTargetParseException} is unchecked exception but we still want to declare it with the
   *     hope to make it checked one day; this type of exception would be properly handled as user
   *     error
   */
  public static UnconfiguredBuildTarget parse(String target) throws BuildTargetParseException {
    int rootPos = target.indexOf(BuildTargetLanguageConstants.ROOT_SYMBOL);
    check(
        rootPos >= 0,
        target,
        "should start with either '%s' or a cell name followed by '%s'",
        BuildTargetLanguageConstants.ROOT_SYMBOL,
        BuildTargetLanguageConstants.ROOT_SYMBOL);

    // if build target starts with `//` then cellName would be empty string
    String cellName = target.substring(0, rootPos);

    int pathPos = rootPos + BuildTargetLanguageConstants.ROOT_SYMBOL.length();

    int flavorSymbolPos = target.lastIndexOf(BuildTargetLanguageConstants.FLAVOR_SYMBOL);

    ImmutableSortedSet<Flavor> flavors;
    if (flavorSymbolPos < 0) {
      // assume no flavors
      flavorSymbolPos = target.length();
      flavors = UnconfiguredBuildTarget.NO_FLAVORS;
    } else {
      String flavorsString = target.substring(flavorSymbolPos + 1);
      flavors =
          Streams.stream(
                  Splitter.on(BuildTargetLanguageConstants.FLAVOR_DELIMITER)
                      .omitEmptyStrings()
                      .trimResults()
                      .split(flavorsString))
              .map(flavor -> (Flavor) InternalFlavor.of(flavor))
              .collect(
                  ImmutableSortedSet.toImmutableSortedSet(UnconfiguredBuildTarget.FLAVOR_ORDERING));

      check(
          !flavors.isEmpty(),
          target,
          "should have flavors specified after '%s' sign",
          BuildTargetLanguageConstants.FLAVOR_SYMBOL);
    }

    int targetSymbolPos =
        target.lastIndexOf(BuildTargetLanguageConstants.TARGET_SYMBOL, flavorSymbolPos - 1);

    check(
        targetSymbolPos >= pathPos && targetSymbolPos < target.length(),
        target,
        "should have '%s' followed by target name",
        BuildTargetLanguageConstants.TARGET_SYMBOL);

    String baseName = target.substring(rootPos, targetSymbolPos);
    String targetName = target.substring(targetSymbolPos + 1, flavorSymbolPos);

    check(
        !targetName.isEmpty(),
        target,
        "should have target name after '%s' sign",
        BuildTargetLanguageConstants.TARGET_SYMBOL);

    return ImmutableUnconfiguredBuildTarget.of(cellName, baseName, targetName, flavors);
  }

  private static void check(boolean condition, String target, String message, Object... args)
      throws BuildTargetParseException {
    if (!condition) {
      throw new BuildTargetParseException(
          String.format(
              "Incorrect syntax for build target '%s': %s", target, String.format(message, args)));
    }
  }
}
