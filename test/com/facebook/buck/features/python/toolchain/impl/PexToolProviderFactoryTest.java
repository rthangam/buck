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

package com.facebook.buck.features.python.toolchain.impl;

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import com.facebook.buck.features.python.toolchain.PythonInterpreter;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.util.FakeProcessExecutor;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class PexToolProviderFactoryTest {

  @Test
  public void testPexArgs() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("python", ImmutableMap.of("pex_flags", "--hello --world")))
            .build();
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    PexToolProviderFactory pexToolProviderFactory = new PexToolProviderFactory();
    PexToolProvider pexToolProvider =
        pexToolProviderFactory
            .createToolchain(
                new ToolchainProviderBuilder()
                    .withToolchain(
                        PythonInterpreter.DEFAULT_NAME,
                        new PythonInterpreterFromConfig(
                            new PythonBuckConfig(buckConfig), new ExecutableFinder()))
                    .build(),
                ToolchainCreationContext.of(
                    ImmutableMap.of(),
                    buckConfig,
                    new FakeProjectFilesystem(),
                    new FakeProcessExecutor(),
                    new AlwaysFoundExecutableFinder(),
                    TestRuleKeyConfigurationFactory.create(),
                    () -> EmptyTargetConfiguration.INSTANCE))
            .get();
    assertThat(
        pexToolProvider
            .getPexTool(resolver, EmptyTargetConfiguration.INSTANCE)
            .getCommandPrefix(resolver.getSourcePathResolver()),
        hasConsecutiveItems("--hello", "--world"));
  }
}
