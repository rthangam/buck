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
package com.facebook.buck.support.state;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.toolchain.ComparableToolchain;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Contains logic to figure out whether two cells are equals enough for a purpose of re-using the
 * daemon's {@link BuckGlobalState}.
 *
 * <p>{@link BuckGlobalStateLifecycleManager} for more information about daemon lifecycle.
 */
class BuckGlobalStateCompatibilityCellChecker {
  private BuckGlobalStateCompatibilityCellChecker() {}

  static boolean areToolchainsCompatibleForCaching(Cell cell1, Cell cell2) {
    ToolchainProvider toolchainProvider = cell1.getToolchainProvider();
    ToolchainProvider otherToolchainProvider = cell2.getToolchainProvider();

    Set<String> toolchains = new HashSet<>();
    toolchains.addAll(toolchainProvider.getToolchainsWithCapability(ComparableToolchain.class));
    toolchains.addAll(
        otherToolchainProvider.getToolchainsWithCapability(ComparableToolchain.class));

    for (String toolchain : toolchains) {
      if (!toolchainsStateEqual(toolchain, toolchainProvider, otherToolchainProvider)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Checks the state of two toolchains is compatible.
   *
   * <p>When comparing two toolchains:
   *
   * <ol>
   *   <li>if both were not created nor failed then return true
   *   <li>if one of the toolchains is failed then true only if second toolchain has the same
   *       exception
   *   <li>ask for presence and:
   *       <ul>
   *         <li>if both are not present then true
   *         <li>if both are present compare them
   *         <li>if one is not present then false
   *       </ul>
   * </ol>
   */
  private static boolean toolchainsStateEqual(
      String toolchain,
      ToolchainProvider toolchainProvider,
      ToolchainProvider otherToolchainProvider) {

    boolean toolchainFailed = toolchainProvider.isToolchainFailed(toolchain);
    boolean otherToolchainFailed = otherToolchainProvider.isToolchainFailed(toolchain);
    boolean toolchainCreated = toolchainProvider.isToolchainCreated(toolchain);
    boolean otherToolchainCreated = otherToolchainProvider.isToolchainCreated(toolchain);

    boolean toolchainInstantiated = toolchainFailed || toolchainCreated;
    boolean otherToolchainInstantiated = otherToolchainFailed || otherToolchainCreated;

    if (!toolchainInstantiated && !otherToolchainInstantiated) {
      return true;
    }

    if (toolchainFailed || otherToolchainFailed) {
      Optional<ToolchainInstantiationException> exception =
          getFailedToolchainException(toolchainProvider, toolchain);
      Optional<ToolchainInstantiationException> otherException =
          getFailedToolchainException(otherToolchainProvider, toolchain);

      return exception.isPresent()
          && otherException.isPresent()
          && exception
              .get()
              .getHumanReadableErrorMessage()
              .equals(otherException.get().getHumanReadableErrorMessage());
    }

    boolean toolchainPresent = toolchainProvider.isToolchainPresent(toolchain);
    boolean otherToolchainPresent = otherToolchainProvider.isToolchainPresent(toolchain);

    // Both toolchains exist, compare them
    if (toolchainPresent && otherToolchainPresent) {
      return toolchainProvider
          .getByName(toolchain)
          .equals(otherToolchainProvider.getByName(toolchain));
    } else {
      return !toolchainPresent && !otherToolchainPresent;
    }
  }

  private static Optional<ToolchainInstantiationException> getFailedToolchainException(
      ToolchainProvider toolchainProvider, String toolchainName) {
    if (toolchainProvider.isToolchainPresent(toolchainName)) {
      return Optional.empty();
    } else {
      return toolchainProvider.getToolchainInstantiationException(toolchainName);
    }
  }
}
