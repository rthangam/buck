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

package com.facebook.buck.io.watchman;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.FileExtensionMatcher;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WatchmanWatcherIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private Watchman watchman;
  private EventBus eventBus;
  private WatchmanEventCollector watchmanEventCollector;

  @Before
  public void setUp() throws InterruptedException, IOException {
    // Create an empty watchman config file.
    Files.write(tmp.getRoot().resolve(".watchmanconfig"), new byte[0]);

    WatchmanFactory watchmanFactory = new WatchmanFactory();
    watchman =
        watchmanFactory.build(
            ImmutableSet.of(tmp.getRoot()),
            EnvVariablesProvider.getSystemEnv(),
            new Console(Verbosity.ALL, System.out, System.err, Ansi.withoutTty()),
            new DefaultClock(),
            Optional.empty());
    assumeTrue(watchman.getTransportPath().isPresent());

    eventBus = new EventBus();
    watchmanEventCollector = new WatchmanEventCollector();
    eventBus.register(watchmanEventCollector);
  }

  @Test
  public void ignoreDotFileInGlob() throws IOException, InterruptedException {
    WatchmanWatcher watcher = createWatchmanWatcher(FileExtensionMatcher.of("swp"));

    // Create a dot-file which should be ignored by the above glob.
    Path path = tmp.getRoot().getFileSystem().getPath("foo/bar/.hello.swp");
    Files.createDirectories(tmp.getRoot().resolve(path).getParent());
    Files.write(tmp.getRoot().resolve(path), new byte[0]);

    // Verify we don't get an event for the path.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertThat(watchmanEventCollector.getEvents(), Matchers.empty());
  }

  @Test
  public void globMatchesWholeName() throws IOException, InterruptedException {
    WatchmanWatcher watcher = createWatchmanWatcher(GlobPatternMatcher.of("*.txt"));

    // Create a dot-file which should be ignored by the above glob.
    Path path = tmp.getRoot().getFileSystem().getPath("foo/bar/hello.txt");
    Files.createDirectories(tmp.getRoot().resolve(path).getParent());
    Files.write(tmp.getRoot().resolve(path), new byte[0]);

    // Verify we still get an event for the created path.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    WatchmanPathEvent event = watchmanEventCollector.getOnlyEvent(WatchmanPathEvent.class);
    Path eventPath = event.getPath();
    assertThat(eventPath, Matchers.equalTo(path));
    assertSame(event.getKind(), Kind.CREATE);
  }

  // Create a watcher for the given ignore paths, clearing the initial overflow event before
  // returning it.
  private WatchmanWatcher createWatchmanWatcher(PathMatcher... ignorePaths)
      throws IOException, InterruptedException {

    WatchmanWatcher watcher =
        new WatchmanWatcher(
            watchman,
            eventBus,
            ImmutableSet.copyOf(ignorePaths),
            ImmutableMap.of(tmp.getRoot(), new WatchmanCursor("n:buckd" + UUID.randomUUID())),
            /* numThreads */ 1);

    // Clear out the initial overflow event.
    watcher.postEvents(
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId()),
        WatchmanWatcher.FreshInstanceAction.NONE);
    watchmanEventCollector.clear();

    return watcher;
  }

  // TODO(buck_team): unite with WatchmanWatcherTest#EventBuffer
  private static final class WatchmanEventCollector {

    private final List<WatchmanEvent> events = new ArrayList<>();

    @Subscribe
    protected void handle(WatchmanEvent event) {
      events.add(event);
    }

    public void clear() {
      events.clear();
    }

    public ImmutableList<WatchmanEvent> getEvents() {
      return ImmutableList.copyOf(events);
    }

    /** Helper to retrieve the only event of the specific class that should be in the list. */
    public <E extends WatchmanEvent> List<E> filterEventsByClass(Class<E> clazz) {
      return events.stream()
          .filter(e -> clazz.isAssignableFrom(e.getClass()))
          .map(e -> (E) e)
          .collect(Collectors.toList());
    }

    /** Helper to retrieve the only event of the specific class that should be in the list. */
    public <E extends WatchmanEvent> E getOnlyEvent(Class<E> clazz) {
      List<E> filteredEvents = filterEventsByClass(clazz);
      assertEquals(
          String.format("Expected only one event of type %s", clazz.getName()),
          1,
          filteredEvents.size());
      return filteredEvents.get(0);
    }
  }
}
