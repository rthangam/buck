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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import org.immutables.value.Value;

/**
 * Captures {@link Extension} and information related to its parsing like all other extensions used
 * in order to load it. The main purpose of extra information is to properly captured all dependent
 * information for caching purposes.
 */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractExtensionData {
  /** @return an extension with its bindings */
  @Value.Parameter
  public abstract Extension getExtension();

  /** @return a path from which the extension was loaded from */
  @Value.Parameter
  public abstract com.google.devtools.build.lib.vfs.Path getPath();

  /** @return a set of dependencies that were required to evaluate this extension */
  @Value.Parameter
  public abstract ImmutableSet<ExtensionData> getDependencies();

  /** @return a load function label that triggered load of this extension */
  @Value.Parameter
  public abstract String getImportString();

  /**
   * @return the list of files loaded in order to parse this extension including the path of this
   *     extension, which is the first element of the list.
   */
  @Value.Parameter
  public abstract ImmutableSet<String> getLoadTransitiveClosure();
}
