/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.List;

public class SourceSortedSetTypeCoercer extends SourceSortedSetConcatable
    implements TypeCoercer<SourceSortedSet> {
  private final TypeCoercer<ImmutableSortedSet<SourcePath>> unnamedHeadersTypeCoercer;
  private final TypeCoercer<ImmutableSortedMap<String, SourcePath>> namedHeadersTypeCoercer;

  SourceSortedSetTypeCoercer(
      TypeCoercer<String> stringTypeCoercer, TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.unnamedHeadersTypeCoercer = new SortedSetTypeCoercer<>(sourcePathTypeCoercer);
    this.namedHeadersTypeCoercer =
        new SortedMapTypeCoercer<>(stringTypeCoercer, sourcePathTypeCoercer);
  }

  @Override
  public Class<SourceSortedSet> getOutputClass() {
    return SourceSortedSet.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return unnamedHeadersTypeCoercer.hasElementClass(types)
        || namedHeadersTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, SourceSortedSet object, Traversal traversal) {
    switch (object.getType()) {
      case UNNAMED:
        unnamedHeadersTypeCoercer.traverse(cellRoots, object.getUnnamedSources().get(), traversal);
        break;
      case NAMED:
        namedHeadersTypeCoercer.traverse(cellRoots, object.getNamedSources().get(), traversal);
        break;
    }
  }

  @Override
  public SourceSortedSet coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof List) {
      return SourceSortedSet.ofUnnamedSources(
          unnamedHeadersTypeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, object));
    } else {
      return SourceSortedSet.ofNamedSources(
          namedHeadersTypeCoercer.coerce(
              cellRoots, filesystem, pathRelativeToProjectRoot, targetConfiguration, object));
    }
  }
}
