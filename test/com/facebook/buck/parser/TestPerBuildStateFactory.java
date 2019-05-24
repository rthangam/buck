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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

public class TestPerBuildStateFactory {
  public static PerBuildState create(Parser parser, Cell cell) {
    return parser
        .getPerBuildStateFactory()
        .create(
            ParsingContext.builder(cell, MoreExecutors.newDirectExecutorService())
                .setSpeculativeParsing(SpeculativeParsing.ENABLED)
                .build(),
            parser.getPermState(),
            ImmutableList.of());
  }
}
