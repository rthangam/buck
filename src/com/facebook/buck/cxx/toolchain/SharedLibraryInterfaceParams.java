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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.google.common.collect.ImmutableList;

public interface SharedLibraryInterfaceParams {

  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  Kind getKind();

  /** @return additional flags to pass to the linker when linking interfaces. */
  ImmutableList<String> getLdflags();

  /** The configured mode for shared library interfaces. */
  enum Type {

    /** Do not use shared library interfaces. */
    DISABLED,

    /** Use shared library interfaces for linking. */
    ENABLED,

    /** Strip undefined symbols from shared library interfaces, and use them for linking */
    DEFINED_ONLY,
  }

  enum Kind {
    ELF
  }
}
