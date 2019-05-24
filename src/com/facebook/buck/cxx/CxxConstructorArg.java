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

package com.facebook.buck.cxx;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.HasDefaultFlavors;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.toolchain.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public interface CxxConstructorArg
    extends CommonDescriptionArg,
        HasDeclaredDeps,
        HasDefaultFlavors,
        HasDefaultPlatform,
        HasTests,
        HasSystemFrameworkAndLibraries {
  @Value.NaturalOrder
  ImmutableSortedSet<SourceWithFlags> getSrcs();

  @Value.Default
  default PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> getPlatformSrcs() {
    return PatternMatchedCollection.of();
  }

  @Value.Default
  default SourceSortedSet getHeaders() {
    return SourceSortedSet.EMPTY;
  }

  /**
   * Raw headers are headers which are used as they are (via compilation flags). Buck doesn't copy
   * them or create symlinks for them. They are public (since managed by compilation flags).
   *
   * @return a list of raw headers
   */
  @Value.Default
  default ImmutableSortedSet<SourcePath> getRawHeaders() {
    return ImmutableSortedSet.of();
  }

  /**
   * A list of include directories to be added to the compile command for compiling this cxx target.
   *
   * @return a list of private include paths for this cxx target.
   */
  @Value.Default
  default ImmutableSortedSet<String> getIncludeDirectories() {
    return ImmutableSortedSet.of();
  }

  @Value.Check
  default void checkHeadersUsage() {
    if (getRawHeaders().isEmpty()) {
      return;
    }

    if (!getHeaders().isEmpty()) {
      throw new HumanReadableException("Cannot use `headers` and `raw_headers` in the same rule.");
    }

    if (!getPlatformHeaders().getPatternsAndValues().isEmpty()) {
      throw new HumanReadableException(
          "Cannot use `platform_headers` and `raw_headers` in the same rule.");
    }
  }

  @Value.Default
  default PatternMatchedCollection<SourceSortedSet> getPlatformHeaders() {
    return PatternMatchedCollection.of();
  }

  Optional<SourcePath> getPrefixHeader();

  Optional<SourcePath> getPrecompiledHeader();

  ImmutableList<StringWithMacros> getCompilerFlags();

  ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>> getLangCompilerFlags();

  ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
      getLangPlatformCompilerFlags();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformCompilerFlags() {
    return PatternMatchedCollection.of();
  }

  ImmutableList<StringWithMacros> getPreprocessorFlags();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformPreprocessorFlags() {
    return PatternMatchedCollection.of();
  }

  ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>> getLangPreprocessorFlags();

  ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
      getLangPlatformPreprocessorFlags();

  ImmutableList<StringWithMacros> getLinkerFlags();

  ImmutableList<StringWithMacros> getPostLinkerFlags();

  ImmutableList<String> getLinkerExtraOutputs();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformLinkerFlags() {
    return PatternMatchedCollection.of();
  }

  Optional<String> getExecutableName();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPostPlatformLinkerFlags() {
    return PatternMatchedCollection.of();
  }

  @Hint(isTargetGraphOnlyDep = true)
  @Value.Default
  default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
    return PatternMatchedCollection.of();
  }

  Optional<String> getHeaderNamespace();

  Optional<Linker.CxxRuntimeType> getCxxRuntimeType();

  ImmutableMap<String, Flavor> getDefaults();

  @Override
  @Value.Derived
  default ImmutableSortedSet<Flavor> getDefaultFlavors() {
    // We don't (yet) use the keys in the default_flavors map, but we
    // plan to eventually support key-value flavors.
    return ImmutableSortedSet.copyOf(getDefaults().values());
  }

  /** @return the C/C++ deps this rule builds against. */
  @Value.Derived
  default CxxDeps getCxxDeps() {
    return getPrivateCxxDeps();
  }

  /** @return C/C++ deps which are *not* propagated to dependents. */
  @Value.Derived
  default CxxDeps getPrivateCxxDeps() {
    return CxxDeps.builder()
        .addDeps(getDeps())
        .addPlatformDeps(getPlatformDeps())
        .addDep(getPrecompiledHeader())
        .build();
  }
}
