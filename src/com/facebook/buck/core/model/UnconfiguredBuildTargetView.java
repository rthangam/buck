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

package com.facebook.buck.core.model;

import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Represents a view over {@link UnconfiguredBuildTarget}.
 *
 * <p>This view adds runtime context information to {@link UnconfiguredBuildTarget}, like absolute
 * cell path where build target belongs to. Because of that, this class should not be used as a
 * long-living data class with the lifetime exceeding the lifetime of running command.
 *
 * <p>This class represents a legacy data structure. In most of the cases you will want to use
 * {@link UnconfiguredBuildTarget} directly.
 */
@ThreadSafe
public interface UnconfiguredBuildTargetView extends Comparable<UnconfiguredBuildTargetView> {

  /** A build target without flavors. */
  UnflavoredBuildTargetView getUnflavoredBuildTargetView();

  /** Set of flavors used with that build target. */
  ImmutableSortedSet<Flavor> getFlavors();

  /**
   * The canonical name of the cell specified in the build target.
   *
   * <p>Note that this name can be different from the name specified on the name. See {@link
   * com.facebook.buck.core.cell.CellPathResolver#getCanonicalCellName} for more information.
   */
  Optional<String> getCell();

  /**
   * The path to the root of the cell where this build target is used.
   *
   * <p>Note that the same build target name can reference different build targets when used in
   * different cells. For example, given a target name {@code external_cell//lib:target}, for a cell
   * that maps {@code external_cell} to a cell with location {@code /cells/cell_a}, this build
   * target would point to a target {@code //lib:target} in the {@code /cells/cell_a} cell (defined
   * in a build file located in {@code /cells/cell_a/lib} folder. If another cell uses the same
   * build target name, but points {@code external_cell} to a different cell, say {@code
   * /cells/cell_b}, then this build target refers to a target {@code //lib:target} in the {@code
   * /cells/cell_b} cell.
   */
  Path getCellPath();

  /**
   * Part of build target name the colon excluding cell name.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava} this returns {@code
   * //third_party/java/guava}.
   */
  String getBaseName();

  /**
   * The path of the directory where this target is located.
   *
   * <p>For example, for {@code //third_party/java/guava:guava} this returns {@link Path} {@code
   * third_party/java/guava}.
   */
  Path getBasePath();

  /**
   * The part of the build target name after the colon.
   *
   * <p>For example, for {@code //third_party/java/guava:guava-latest} this returns {@code
   * guava-latest}.
   */
  String getShortName();

  /**
   * The short name with flavors.
   *
   * <p>For example, for {@code //third_party/java/guava:guava-latest#flavor} this returns {@code
   * guava-latest#flavor}.
   */
  String getShortNameAndFlavorPostfix();

  /**
   * The full name of the build target including the cell name and flavors.
   *
   * <p>For example, for {@code cell//third_party/java/guava:guava-latest#flavor} this returns
   * {@code cell//third_party/java/guava:guava-latest#flavor}.
   */
  String getFullyQualifiedName();

  /** Whether this target contains flavors. */
  boolean isFlavored();

  /**
   * Verifies that this build target has no flavors.
   *
   * @return this build target
   * @throws IllegalStateException if a build target has flavors
   */
  UnconfiguredBuildTargetView assertUnflavored();

  /**
   * Creates a new build target by copying all of the information from this build target and
   * replacing the short name with the given name.
   *
   * @param shortName short name of the new build target
   */
  UnconfiguredBuildTargetView withShortName(String shortName);

  /**
   * Creates a new build target by copying all of the information from this build target and using
   * the provided flavors as flavors in the new build target.
   *
   * @param flavors flavors to use when creating a new build target
   */
  UnconfiguredBuildTargetView withFlavors(Flavor... flavors);

  /**
   * Creates a new build target by copying all of the information from this build target and using
   * the provided flavors as flavors in the new build target.
   *
   * @param flavors flavors to use when creating a new build target
   */
  UnconfiguredBuildTargetView withFlavors(Iterable<? extends Flavor> flavors);

  /**
   * Creates a new build target by using the provided {@link UnflavoredBuildTargetView} and flavors
   * from this build target.
   */
  UnconfiguredBuildTargetView withUnflavoredBuildTarget(UnflavoredBuildTargetView target);

  /**
   * Creates a new build target by copying all of the information from this build target and
   * removing the name of the cell.
   */
  UnconfiguredBuildTargetView withoutCell();

  /**
   * Creates {@link BuildTarget} by attaching {@link TargetConfiguration} to this unconfigured build
   * target.
   */
  BuildTarget configure(TargetConfiguration targetConfiguration);

  /** Return a data object that backs current view */
  UnconfiguredBuildTarget getData();
}
