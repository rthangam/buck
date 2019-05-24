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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.keys.hasher.RuleKeyHasher.Container;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher.Wrapper;
import com.facebook.buck.util.Scope;

/** Does nothing. */
public class NoopRuleKeyScopedHasher implements RuleKeyScopedHasher {

  public static final NoopRuleKeyScopedHasher INSTANCE = new NoopRuleKeyScopedHasher();

  private NoopRuleKeyScopedHasher() {}

  @Override
  public Scope keyScope(String key) {
    return Scope.NOOP;
  }

  @Override
  public Scope wrapperScope(Wrapper wrapper) {
    return Scope.NOOP;
  }

  @Override
  public ContainerScope containerScope(Container container) {
    return NOOP_CONTAINER_SCOPE;
  }

  private static final ContainerScope NOOP_CONTAINER_SCOPE =
      new ContainerScope() {
        @Override
        public Scope elementScope() {
          return Scope.NOOP;
        }

        @Override
        public void close() {}
      };
}
