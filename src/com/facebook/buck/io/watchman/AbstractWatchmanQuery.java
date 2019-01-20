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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractWatchmanQuery {

  abstract String getQueryPath();

  abstract ImmutableMap<String, Object> getQueryParams();

  public ImmutableList<Object> toList(String sinceCursor) {
    return ImmutableList.of(
        "query",
        getQueryPath(),
        ImmutableMap.<String, Object>builder()
            .put("since", sinceCursor)
            .putAll(getQueryParams())
            .build());
  }
}
