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

package com.facebook.buck.support.state;

import com.facebook.buck.command.config.ConfigDifference;
import com.facebook.buck.command.config.ConfigDifference.ConfigChange;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Guards access to the {@link BuckGlobalState} instance behind cell configuration checks. Creates
 * or re-creates the daemon state as necessary if the cell configuration changes.
 */
@ThreadSafe
public class BuckGlobalStateLifecycleManager {
  private static final Logger LOG = Logger.get(BuckGlobalStateLifecycleManager.class);

  @Nullable private volatile BuckGlobalState buckGlobalState;

  /** Indicates whether a daemon's {@link BuckGlobalState} is reused, or why it can't be reused */
  public enum LifecycleStatus {
    REUSED,
    NEW,
    INVALIDATED_NO_WATCHMAN,
    INVALIDATED_WATCHMAN_RESTARTED,
    INVALIDATED_FILESYSTEM_CHANGED,
    INVALIDATED_BUCK_CONFIG_CHANGED,
    INVALIDATED_TOOLCHAINS_INCOMPATIBLE;

    private String toHumanReadableError() {
      switch (this) {
        case REUSED:
          return "Reusing existing daemon's stored global state";
        case NEW:
          return "Initializing daemon state for the first time";
        case INVALIDATED_NO_WATCHMAN:
          return "Watchman failed to start";
        case INVALIDATED_WATCHMAN_RESTARTED:
          return "Watchman restarted";
        case INVALIDATED_FILESYSTEM_CHANGED:
          return "The project directory changed between invocations";
        case INVALIDATED_BUCK_CONFIG_CHANGED:
          return "Buck configuration options changed between invocations";
        case INVALIDATED_TOOLCHAINS_INCOMPATIBLE:
          return "Available / configured toolchains changed between invocations";
        default:
          throw new AssertionError(String.format("Unknown value: %s", this));
      }
    }

    /** Get the string to be logged as an event, if an event should be logged. */
    public Optional<String> getLifecycleStatusString() {
      switch (this) {
        case REUSED:
          return Optional.empty();
        case NEW:
          return Optional.of("DaemonInitialized");
        case INVALIDATED_NO_WATCHMAN:
          return Optional.of("DaemonWatchmanInvalidated");
        case INVALIDATED_WATCHMAN_RESTARTED:
          return Optional.of("DaemonWatchmanRestarted");
        case INVALIDATED_FILESYSTEM_CHANGED:
          return Optional.of("DaemonFilesystemInvalidated");
        case INVALIDATED_BUCK_CONFIG_CHANGED:
          return Optional.of("DaemonBuckConfigInvalidated");
        case INVALIDATED_TOOLCHAINS_INCOMPATIBLE:
          return Optional.of("DaemonToolchainsInvalidated");
        default:
          throw new AssertionError(String.format("Unknown value: %s", this));
      }
    }
  }

  public synchronized boolean hasStoredBuckGlobalState() {
    return buckGlobalState != null;
  }

  public synchronized Optional<BuckConfig> getBuckConfig() {
    return Optional.ofNullable(buckGlobalState)
        .map(BuckGlobalState::getRootCell)
        .map(Cell::getBuckConfig);
  }

  /** Get or create Daemon. */
  public synchronized Pair<BuckGlobalState, LifecycleStatus> getBuckGlobalState(
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Watchman watchman,
      Console console,
      Clock clock,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer) {

    BuckGlobalState currentState = buckGlobalState;
    LifecycleStatus lifecycleStatus =
        buckGlobalState == null ? LifecycleStatus.NEW : LifecycleStatus.REUSED;

    // If Watchman failed to start, drop all caches
    if (buckGlobalState != null && watchman == WatchmanFactory.NULL_WATCHMAN) {
      // TODO(buck_team): make Watchman a requirement
      LOG.info("Restarting daemon state because watchman failed to start");
      lifecycleStatus = LifecycleStatus.INVALIDATED_NO_WATCHMAN;
      buckGlobalState = null;
    }

    // If the state was previously created without Watchman, but now Watchman becomes available,
    // drop all caches. Ideally, Watchman should be a requirement.
    if (buckGlobalState != null
        && watchman != WatchmanFactory.NULL_WATCHMAN
        && !buckGlobalState.getUsesWatchman()) {
      LOG.info("Restarting daemon state because watchman was restarted");
      lifecycleStatus = LifecycleStatus.INVALIDATED_WATCHMAN_RESTARTED;
      buckGlobalState = null;
    }

    Map<String, ConfigChange> configDifference = ImmutableMap.of();
    // If Buck config has changed or SDKs have changed, drop all caches
    if (buckGlobalState != null) {
      // Check if current cell and cell for cached state are similar enough to re-use global state.
      // Considers only a subset of the information provided by the cells.
      Cell stateCell = buckGlobalState.getRootCell();
      if (!stateCell.getFilesystem().equals(rootCell.getFilesystem())) {
        lifecycleStatus = LifecycleStatus.INVALIDATED_FILESYSTEM_CHANGED;
      } else {
        configDifference =
            ConfigDifference.compareForCaching(stateCell.getBuckConfig(), rootCell.getBuckConfig());
        if (!configDifference.isEmpty()) {
          lifecycleStatus = LifecycleStatus.INVALIDATED_BUCK_CONFIG_CHANGED;
        } else if (!BuckGlobalStateCompatibilityCellChecker.areToolchainsCompatibleForCaching(
            stateCell, rootCell)) {
          lifecycleStatus = LifecycleStatus.INVALIDATED_TOOLCHAINS_INCOMPATIBLE;
        }
      }

      if (lifecycleStatus != LifecycleStatus.REUSED) {
        LOG.info(
            "Shutting down and restarting daemon state on config or directory graphBuilder change (%s != %s)",
            stateCell, rootCell);
        buckGlobalState = null;
      }
    }

    // if we restart daemon, notify user that caches are screwed
    if (buckGlobalState == null
        && currentState != null
        && console.getVerbosity().shouldPrintStandardInformation()) {

      StringBuilder warning =
          new StringBuilder(
              String.format(
                  "Invalidating internal cached state: %s. This may cause slower builds.",
                  lifecycleStatus.toHumanReadableError()));
      if (!configDifference.isEmpty()) {
        warning
            .append(System.lineSeparator())
            .append(ConfigDifference.formatConfigDiffShort(configDifference, 2));
        LOG.info("Config changes: %s", ConfigDifference.formatConfigDiff(configDifference));
      }
      // Use the raw stream because otherwise this will stop superconsole from ever printing again
      console
          .getStdErr()
          .getRawStream()
          .println(console.getAnsi().asWarningText(warning.toString()));
    }

    // start new daemon, clean old one if needed
    if (buckGlobalState == null) {
      LOG.debug(
          "Starting up daemon state for project root [%s]", rootCell.getFilesystem().getRootPath());

      // try to reuse webserver from previous state
      // does it need to be in daemon at all?
      Optional<WebServer> webServer =
          currentState != null && shouldReuseWebServer(rootCell)
              ? currentState.getWebServer()
              : Optional.empty();

      if (currentState != null) {
        currentState.close();
      }

      buckGlobalState =
          BuckGlobalStateFactory.create(
              rootCell,
              knownRuleTypesProvider,
              watchman,
              webServer,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              clock);
    }

    return new Pair<>(buckGlobalState, lifecycleStatus);
  }

  /** Manually reset the {@link BuckGlobalState}, used for testing. */
  public synchronized void resetBuckGlobalState() {
    if (buckGlobalState != null) {
      LOG.info("Closing daemon's global state on reset request.");
      buckGlobalState.close();
    }
    buckGlobalState = null;
  }

  private boolean shouldReuseWebServer(Cell newCell) {
    if (newCell == null || buckGlobalState == null) {
      return false;
    }
    OptionalInt portFromOldConfig =
        getBuckConfig()
            .map(BuckGlobalStateFactory::getValidWebServerPort)
            .orElse(OptionalInt.empty());
    OptionalInt portFromUpdatedConfig =
        BuckGlobalStateFactory.getValidWebServerPort(newCell.getBuckConfig());

    return portFromOldConfig.equals(portFromUpdatedConfig);
  }
}
