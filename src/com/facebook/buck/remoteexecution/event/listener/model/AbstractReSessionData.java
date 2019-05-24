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

package com.facebook.buck.remoteexecution.event.listener.model;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import org.immutables.value.Value;

/** Information specific to Remote Execution. */
@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
interface AbstractReSessionData {

  @Value.Parameter
  RESessionID getReSessionId();

  @Value.Parameter
  String getReSessionLabel();
}
