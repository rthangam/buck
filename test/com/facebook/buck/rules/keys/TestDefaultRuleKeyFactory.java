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

import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.util.hashing.FileHashLoader;

public class TestDefaultRuleKeyFactory extends DefaultRuleKeyFactory {

  public TestDefaultRuleKeyFactory(FileHashLoader hashLoader, SourcePathRuleFinder ruleFinder) {
    this(0, hashLoader, ruleFinder);
  }

  public TestDefaultRuleKeyFactory(
      int seed, FileHashLoader hashLoader, SourcePathRuleFinder ruleFinder) {
    super(
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.createWithSeed(seed)),
        hashLoader,
        ruleFinder);
  }
}
