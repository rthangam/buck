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

package com.facebook.buck.util.cache.impl;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.util.cache.FileHashCacheEngine;
import com.facebook.buck.util.cache.HashCodeAndFileType;
import com.facebook.buck.util.cache.JarHashCodeAndFileType;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

class LoadingCacheFileHashCache implements FileHashCacheEngine {

  private final LoadingCache<Path, HashCodeAndFileType> loadingCache;
  private final LoadingCache<Path, Long> sizeCache;
  private final Map<Path, Set<Path>> parentToChildCache = new ConcurrentHashMap<>();

  private LoadingCacheFileHashCache(
      ValueLoader<HashCodeAndFileType> hashLoader, ValueLoader<Long> sizeLoader) {
    loadingCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, HashCodeAndFileType>() {
                  @Override
                  public HashCodeAndFileType load(Path path) {
                    HashCodeAndFileType hashCodeAndFileType = hashLoader.load(path);
                    updateParent(path);
                    return hashCodeAndFileType;
                  }
                });
    sizeCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Path, Long>() {
                  @Override
                  public Long load(Path path) {
                    long size = sizeLoader.load(path);
                    updateParent(path);
                    return size;
                  }
                });
  }

  private void updateParent(Path path) {
    Path parent = path.getParent();
    if (parent != null) {
      Set<Path> children =
          parentToChildCache.computeIfAbsent(parent, key -> Sets.newConcurrentHashSet());
      children.add(path);
    }
  }

  public static FileHashCacheEngine createWithStats(
      ValueLoader<HashCodeAndFileType> hashLoader, ValueLoader<Long> sizeLoader) {
    return new StatsTrackingFileHashCacheEngine(
        new LoadingCacheFileHashCache(hashLoader, sizeLoader), "old");
  }

  @Override
  public void put(Path path, HashCodeAndFileType value) {
    loadingCache.put(path, value);
    updateParent(path);
  }

  @Override
  public void putSize(Path path, long value) {
    sizeCache.put(path, value);
    updateParent(path);
  }

  @Override
  public void invalidateWithParents(Path path) {
    Iterable<Path> pathsToInvalidate =
        Maps.filterEntries(
                loadingCache.asMap(),
                entry -> {
                  Objects.requireNonNull(entry);

                  // If we get a invalidation for a file which is a prefix of our current one, this
                  // means the invalidation is of a symlink which points to a directory (since
                  // events
                  // won't be triggered for directories).  We don't fully support symlinks, however,
                  // we do support some limited flows that use them to point to read-only storage
                  // (e.g. the `project.read_only_paths`).  For these limited flows to work
                  // correctly,
                  // we invalidate.
                  if (entry.getKey().startsWith(path)) {
                    return true;
                  }

                  // Otherwise, we want to invalidate the entry if the path matches it.  We also
                  // invalidate any directories that contain this entry, so use the following
                  // comparison to capture both these scenarios.
                  return path.startsWith(entry.getKey());
                })
            .keySet();
    for (Path pathToInvalidate : pathsToInvalidate) {
      invalidate(pathToInvalidate);
    }
  }

  @Override
  public void invalidate(Path path) {
    loadingCache.invalidate(path);
    sizeCache.invalidate(path);
    Set<Path> children = parentToChildCache.remove(path);

    // recursively invalidate all recorded children (underlying files and subfolders)
    if (children != null) {
      children.forEach(this::invalidate);
    }

    Path parent = path.getParent();
    if (parent != null) {
      Set<Path> siblings = parentToChildCache.get(parent);
      if (siblings != null) {
        siblings.remove(path);
      }
    }
  }

  @Override
  public HashCode get(Path path) throws IOException {
    HashCode sha1;
    try {
      sha1 = loadingCache.get(path.normalize()).getHashCode();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }

    return Preconditions.checkNotNull(sha1, "Failed to find a HashCode for %s.", path);
  }

  @Override
  public HashCode getForArchiveMember(Path archiveRelativePath, Path memberPath)
      throws IOException {
    Path relativeFilePath = archiveRelativePath.normalize();
    try {
      JarHashCodeAndFileType fileHashCodeAndFileType =
          (JarHashCodeAndFileType) loadingCache.get(relativeFilePath);
      HashCodeAndFileType memberHashCodeAndFileType =
          fileHashCodeAndFileType.getContents().get(memberPath);
      if (memberHashCodeAndFileType == null) {
        throw new NoSuchFileException(archiveRelativePath.toString());
      }

      return memberHashCodeAndFileType.getHashCode();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  @Override
  public long getSize(Path relativePath) throws IOException {
    try {
      return sizeCache.get(relativePath.normalize());
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  @Override
  public void invalidateAll() {
    loadingCache.invalidateAll();
    sizeCache.invalidateAll();
    parentToChildCache.clear();
  }

  @Override
  public ConcurrentMap<Path, HashCodeAndFileType> asMap() {
    return loadingCache.asMap();
  }

  @Override
  public HashCodeAndFileType getIfPresent(Path path) {
    return loadingCache.getIfPresent(path);
  }

  @Override
  public Long getSizeIfPresent(Path path) {
    return sizeCache.getIfPresent(path);
  }

  @Override
  public List<AbstractBuckEvent> getStatsEvents() {
    return Collections.emptyList();
  }
}
