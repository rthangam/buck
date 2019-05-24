/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.jvm.groovy;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GroovyBuckConfigTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void refuseToContinueWhenInsufficientInformationToFindGroovycIsProvided() {
    thrown.expectMessage(
        allOf(
            containsString("Unable to locate groovy compiler"),
            containsString("GROOVY_HOME is not set, and groovy.groovy_home was not provided")));

    ImmutableMap<String, String> environment = ImmutableMap.of();
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig = ImmutableMap.of();
    GroovyBuckConfig groovyBuckConfig = createGroovyConfig(environment, rawConfig);

    groovyBuckConfig.getGroovyc(EmptyTargetConfiguration.INSTANCE);
  }

  @Test
  public void refuseToContinueWhenInformationResultsInANonExistentGroovycPath() {
    String invalidPath = temporaryFolder.getRoot().toAbsolutePath() + "DoesNotExist";
    Path invalidDir = Paths.get(invalidPath);
    Path invalidGroovyc = invalidDir.resolve(MorePaths.pathWithPlatformSeparators("bin/groovyc"));
    thrown.expectMessage(containsString("Unable to locate " + invalidGroovyc + " on PATH"));

    ImmutableMap<String, String> environment = ImmutableMap.of("GROOVY_HOME", invalidPath);
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig = ImmutableMap.of();
    GroovyBuckConfig groovyBuckConfig = createGroovyConfig(environment, rawConfig);

    groovyBuckConfig.getGroovyc(EmptyTargetConfiguration.INSTANCE);
  }

  @Test
  public void byDefaultFindGroovycFromGroovyHome() {
    String systemGroovyHome = EnvVariablesProvider.getSystemEnv().get("GROOVY_HOME");
    assumeTrue(systemGroovyHome != null);

    ImmutableMap<String, String> environment = ImmutableMap.of("GROOVY_HOME", systemGroovyHome);
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig = ImmutableMap.of();
    GroovyBuckConfig groovyBuckConfig = createGroovyConfig(environment, rawConfig);

    // it's enough that this doesn't throw.
    groovyBuckConfig.getGroovyc(EmptyTargetConfiguration.INSTANCE);
  }

  @Test
  public void explicitConfigurationOverridesTheEnvironment() {
    String systemGroovyHome = EnvVariablesProvider.getSystemEnv().get("GROOVY_HOME");
    assumeTrue(systemGroovyHome != null);

    // deliberately break the env
    ImmutableMap<String, String> environment = ImmutableMap.of("GROOVY_HOME", "/oops");
    ImmutableMap<String, ImmutableMap<String, String>> rawConfig =
        ImmutableMap.of("groovy", ImmutableMap.of("groovy_home", systemGroovyHome));
    GroovyBuckConfig groovyBuckConfig = createGroovyConfig(environment, rawConfig);

    // it's enough that this doesn't throw.
    groovyBuckConfig.getGroovyc(EmptyTargetConfiguration.INSTANCE);
  }

  private GroovyBuckConfig createGroovyConfig(
      ImmutableMap<String, String> environment,
      ImmutableMap<String, ImmutableMap<String, String>> rawConfig) {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(RawConfig.of(rawConfig))
            .setFilesystem(projectFilesystem)
            .setEnvironment(environment)
            .build();

    return new GroovyBuckConfig(buckConfig);
  }
}
