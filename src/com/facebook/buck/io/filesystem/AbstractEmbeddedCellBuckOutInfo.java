/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Information to create the buck-out of cell when it's going to be embedded in the root cell
 * buck-out.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractEmbeddedCellBuckOutInfo {
  public abstract Path getMainCellRoot();

  public abstract BuckPaths getMainCellBuckPaths();

  public abstract String getCellName();

  /** Returns an absolute path to the cell's buck-out embedded inside the root's buck-out */
  @Value.Derived
  public Path getCellBuckOut() {
    Path relativeCellBuckOut =
        getMainCellBuckPaths().getEmbeddedCellsBuckOutBaseDir().resolve(getCellName());
    return getMainCellRoot().resolve(relativeCellBuckOut);
  }
}
