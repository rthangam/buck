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
package com.facebook.buck.jvm.java;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

@BuckStyleImmutable
@Immutable
public abstract class AbstractJavacLanguageLevelOptions implements AddsToRuleKey {

  public static final JavacLanguageLevelOptions DEFAULT =
      JavacLanguageLevelOptions.builder().build();

  // Default combined source and target level.
  public static final String TARGETED_JAVA_VERSION = "7";

  @Value.Default
  @AddToRuleKey
  public String getSourceLevel() {
    return TARGETED_JAVA_VERSION;
  }

  @Value.Default
  @AddToRuleKey
  public String getTargetLevel() {
    return TARGETED_JAVA_VERSION;
  }
}
