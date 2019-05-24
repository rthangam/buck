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

package com.facebook.buck.core.plugin.impl;

import com.facebook.buck.core.plugin.BuckPluginManager;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.annotation.Nullable;
import org.pf4j.CompoundPluginDescriptorFinder;
import org.pf4j.CompoundPluginRepository;
import org.pf4j.DefaultPluginManager;
import org.pf4j.DefaultPluginRepository;
import org.pf4j.ExtensionFinder;
import org.pf4j.JarPluginLoader;
import org.pf4j.JarPluginRepository;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginLoader;
import org.pf4j.PluginRepository;
import org.pf4j.VersionManager;

public class DefaultBuckPluginManager extends DefaultPluginManager implements BuckPluginManager {

  private static final Logger LOG = Logger.get(DefaultBuckPluginManager.class);

  private final LoadingCache<Class<?>, List<?>> extensionsCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Class<?>, List<?>>() {
                @Override
                public List<?> load(Class<?> type) {
                  long start = System.currentTimeMillis();
                  try {
                    return DefaultBuckPluginManager.super.getExtensions(type);
                  } finally {
                    LOG.info(
                        "Time to load instances of %s: %s",
                        type, System.currentTimeMillis() - start);
                  }
                }
              });

  @Override
  protected ExtensionFinder createExtensionFinder() {
    return new BuckExtensionFinder(this);
  }

  @Override
  protected CompoundPluginDescriptorFinder createPluginDescriptorFinder() {
    return new CompoundPluginDescriptorFinder().add(new ManifestPluginDescriptorFinder());
  }

  @Override
  protected PluginRepository createPluginRepository() {
    CompoundPluginRepository repository =
        new CompoundPluginRepository()
            .add(new DefaultPluginRepository(getPluginsRoot(), isDevelopment()))
            .add(new JarPluginRepository(getPluginsRoot()));

    @Nullable String externalPluginsRoot = System.getProperty("buck.externalPluginsDir");

    if (externalPluginsRoot != null) {
      Path externalPluginsRootPath = Paths.get(externalPluginsRoot).toAbsolutePath();
      repository
          .add(new DefaultPluginRepository(externalPluginsRootPath, isDevelopment()))
          .add(new JarPluginRepository(externalPluginsRootPath));
    }

    return repository;
  }

  @Override
  protected PluginLoader createPluginLoader() {
    return new JarPluginLoader(this);
  }

  @Override
  protected VersionManager createVersionManager() {
    // Buck modules do not support versions
    return (__, ___) -> true;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> List<T> getExtensions(Class<T> type) {
    return (List<T>) extensionsCache.getUnchecked(type);
  }
}
