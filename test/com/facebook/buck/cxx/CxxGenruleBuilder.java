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

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;

public class CxxGenruleBuilder
    extends AbstractNodeBuilder<
        CxxGenruleDescriptionArg.Builder,
        CxxGenruleDescriptionArg,
        CxxGenruleDescription,
        BuildRule> {

  public CxxGenruleBuilder(BuildTarget target, FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms) {
    super(
        new CxxGenruleDescription(
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            new ToolchainProviderBuilder()
                .withToolchain(
                    CxxPlatformsProvider.DEFAULT_NAME,
                    CxxPlatformsProvider.of(
                        CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM, cxxPlatforms))
                .build(),
            new NoSandboxExecutionStrategy()),
        target);
  }

  public CxxGenruleBuilder(BuildTarget target) {
    this(target, CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public CxxGenruleBuilder setOut(String out) {
    getArgForPopulating().setOut(out);
    return this;
  }

  public CxxGenruleBuilder setCmd(StringWithMacros cmd) {
    getArgForPopulating().setCmd(cmd);
    return this;
  }

  public CxxGenruleBuilder setCacheable(boolean cacheable) {
    getArgForPopulating().setCacheable(cacheable);
    return this;
  }
}
