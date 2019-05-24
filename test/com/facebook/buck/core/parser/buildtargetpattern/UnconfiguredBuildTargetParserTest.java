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
package com.facebook.buck.core.parser.buildtargetpattern;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.google.common.collect.ImmutableSortedSet;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class UnconfiguredBuildTargetParserTest {

  @Rule public ExpectedException exception = ExpectedException.none();

  @SuppressWarnings("unused")
  private Object[] dataParseSuccess() {
    return new Object[] {
      new Object[] {
        "cell//path/to:target",
        ImmutableUnconfiguredBuildTarget.of(
            "cell", "//path/to", "target", UnconfiguredBuildTarget.NO_FLAVORS)
      },
      new Object[] {
        "cell//path/to:target#flavor1,flavor2",
        ImmutableUnconfiguredBuildTarget.of(
            "cell",
            "//path/to",
            "target",
            ImmutableSortedSet.of(InternalFlavor.of("flavor1"), InternalFlavor.of("flavor2")))
      },
      new Object[] {
        "//path/to:target",
        ImmutableUnconfiguredBuildTarget.of(
            "", "//path/to", "target", UnconfiguredBuildTarget.NO_FLAVORS)
      },
      new Object[] {
        "//path/to:target#flavor",
        ImmutableUnconfiguredBuildTarget.of(
            "", "//path/to", "target", ImmutableSortedSet.of(InternalFlavor.of("flavor")))
      },
      new Object[] {
        "//:target",
        ImmutableUnconfiguredBuildTarget.of("", "//", "target", UnconfiguredBuildTarget.NO_FLAVORS)
      },
      new Object[] {
        "cell//:target",
        ImmutableUnconfiguredBuildTarget.of(
            "cell", "//", "target", UnconfiguredBuildTarget.NO_FLAVORS)
      },
      new Object[] {
        "cell//path:target",
        ImmutableUnconfiguredBuildTarget.of(
            "cell", "//path", "target", UnconfiguredBuildTarget.NO_FLAVORS)
      }
    };
  }

  @Test
  @Parameters(method = "dataParseSuccess")
  @TestCaseName("parsingSucceeds({0})")
  public void parsingSucceeds(String buildTarget, UnconfiguredBuildTarget expected)
      throws BuildTargetParseException {
    assertEquals(expected, UnconfiguredBuildTargetParser.parse(buildTarget));
  }

  @Test
  @Parameters({
    "",
    "path/to:target",
    "//",
    "//path",
    "//:",
    "path/to",
    "cell//path/to",
    "path/to#",
    "#",
    "//#",
    "//:#flavor",
  })
  @TestCaseName("parsingFails({0})")
  public void parsingFails(String buildTarget) throws BuildTargetParseException {
    exception.expect(BuildTargetParseException.class);
    UnconfiguredBuildTargetParser.parse(buildTarget);
  }
}
