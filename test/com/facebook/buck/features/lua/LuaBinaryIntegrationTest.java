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

package com.facebook.buck.features.lua;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class LuaBinaryIntegrationTest {

  private ProjectWorkspace workspace;
  private Path lua;
  private boolean luaDevel;

  @Parameterized.Parameters(name = "{0} {1}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        Arrays.asList(LuaBinaryDescription.StarterType.values()),
        Arrays.asList(NativeLinkStrategy.values()));
  }

  @Parameterized.Parameter public LuaBinaryDescription.StarterType starterType;

  @Parameterized.Parameter(value = 1)
  public NativeLinkStrategy nativeLinkStrategy;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {

    // We don't currently support windows.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    // Verify that a Lua interpreter is available on the system.
    ExecutableFinder finder = new ExecutableFinder();
    Optional<Path> luaOptional =
        finder.getOptionalExecutable(Paths.get("lua"), EnvVariablesProvider.getSystemEnv());
    assumeTrue(luaOptional.isPresent());
    lua = luaOptional.get();

    // Try to detect if a Lua devel package is available, which is needed to C/C++ support.
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    CxxPlatform cxxPlatform =
        DefaultCxxPlatforms.build(
            Platform.detect(), new CxxBuckConfig(FakeBuckConfig.builder().build()));
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.<String>builder()
                    .addAll(
                        cxxPlatform
                            .getCc()
                            .resolve(resolver, EmptyTargetConfiguration.INSTANCE)
                            .getCommandPrefix(resolver.getSourcePathResolver()))
                    .add("-includelua.h", "-E", "-")
                    .build())
            .setRedirectInput(ProcessBuilder.Redirect.PIPE)
            .build();
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.LaunchedProcess launchedProcess = executor.launchProcess(params);
    launchedProcess.getOutputStream().close();
    int exitCode = executor.waitForLaunchedProcess(launchedProcess).getExitCode();
    luaDevel = exitCode == 0;
    if (starterType == LuaBinaryDescription.StarterType.NATIVE) {
      assumeTrue("Lua devel package required for native starter", luaDevel);
    }

    // Setup the workspace.
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "lua_binary", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        Joiner.on(System.lineSeparator())
            .join(
                ImmutableList.of(
                    "[lua]",
                    "  starter_type = " + starterType.toString().toLowerCase(),
                    "  native_link_strategy = " + nativeLinkStrategy.toString().toLowerCase())),
        ".buckconfig");
    LuaPlatform platform =
        getLuaBuckConfig()
            .getPlatform(
                EmptyTargetConfiguration.INSTANCE,
                CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(DefaultCxxPlatforms.FLAVOR));
    assertThat(platform.getStarterType(), Matchers.equalTo(Optional.of(starterType)));
    assertThat(platform.getNativeLinkStrategy(), Matchers.equalTo(nativeLinkStrategy));
  }

  @Test
  public void stdout() throws Exception {
    workspace.writeContentsToPath("require 'os'; io.stdout:write('hello world')", "simple.lua");
    ProcessResult result = workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void stderr() throws Exception {
    workspace.writeContentsToPath("require 'os'; io.stderr:write('hello world')", "simple.lua");
    Path path = workspace.buildAndReturnOutput("//:simple");
    ProcessExecutor.Result result = workspace.runCommand(path.toString());
    assertThat(
        result.getStdout().orElse("") + result.getStderr().orElse(""),
        result.getStderr().orElse("").trim(),
        Matchers.endsWith("hello world"));
  }

  @Test
  public void errorCode() throws Exception {
    workspace.writeContentsToPath("require 'os'\nos.exit(5)", "simple.lua");
    workspace.runBuckBuild("//:simple").assertSuccess();
    ProcessResult result = workspace.runBuckCommand("run", "//:simple");
    assertEquals(result.getExitCode(), ExitCode.BUILD_ERROR);
  }

  @Test
  public void error() throws Exception {
    workspace.writeContentsToPath("blah blah garbage", "simple.lua");
    workspace.runBuckBuild("//:simple").assertSuccess();
    workspace.runBuckCommand("run", "//:simple").assertFailure();
  }

  @Test
  public void args() throws Exception {
    workspace.writeContentsToPath("for i=-1,#arg do print(arg[i]) end", "simple.lua");
    Path arg0 = workspace.buildAndReturnOutput("//:simple");

    // no args...
    ProcessResult result = workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        Splitter.on(System.lineSeparator()).splitToList(result.getStdout().trim()),
        Matchers.contains(
            ImmutableList.of(
                Matchers.anyOf(Matchers.equalTo(lua.toString()), Matchers.equalTo("nil")),
                Matchers.endsWith(arg0.toString()))));

    // with args...
    result = workspace.runBuckCommand("run", "//:simple", "--", "hello", "world").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        Splitter.on(System.lineSeparator()).splitToList(result.getStdout().trim()),
        Matchers.contains(
            ImmutableList.of(
                Matchers.anyOf(Matchers.equalTo(lua.toString()), Matchers.equalTo("nil")),
                Matchers.endsWith(arg0.toString()),
                Matchers.equalTo("hello"),
                Matchers.equalTo("world"))));
  }

  @Test
  public void nativeExtension() {
    assumeTrue(luaDevel);
    ProcessResult result = workspace.runBuckCommand("run", "//:native").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void nativeExtensionWithDep() {
    assumeThat(starterType, Matchers.not(Matchers.equalTo(LuaBinaryDescription.StarterType.PURE)));
    assumeTrue(luaDevel);
    ProcessResult result = workspace.runBuckCommand("run", "//:native_with_dep").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void packagedFormat() throws Exception {
    Path output =
        workspace.buildAndReturnOutput(
            "-c", "lua.package_style=standalone", "-c", "lua.packager=//:packager", "//:simple");
    ImmutableMap<String, ImmutableMap<String, String>> components =
        ObjectMappers.readValue(
            output, new TypeReference<ImmutableMap<String, ImmutableMap<String, String>>>() {});
    assertThat(components.get("modules").keySet(), Matchers.equalTo(ImmutableSet.of("simple.lua")));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertEqualsInsteadOfAssertTrue")
  public void switchingBetweenPacakgedFormats() throws Exception {

    // Run an inital build using the standalone packaging style.
    String standaloneFirst =
        workspace.getFileContents(
            workspace.buildAndReturnOutput(
                "-c",
                "lua.package_style=standalone",
                "-c",
                "lua.packager=//:packager",
                "//:simple"));

    // Now rebuild with just changing to an in-place packaging style.
    String inplaceFirst =
        workspace.getFileContents(
            workspace.buildAndReturnOutput("-c", "lua.package_style=inplace", "//:simple"));

    // Now rebuild again, switching back to standalone, and verify the output matches the original
    // build's output.
    String standaloneSecond =
        workspace.getFileContents(
            workspace.buildAndReturnOutput(
                "-c",
                "lua.package_style=standalone",
                "-c",
                "lua.packager=//:packager",
                "//:simple"));
    assertTrue(standaloneFirst.equals(standaloneSecond));

    // Now rebuild again, switching back to in-place, and verify the output matches the original
    // build's output.
    String inplaceSecond =
        workspace.getFileContents(
            workspace.buildAndReturnOutput("-c", "lua.package_style=inplace", "//:simple"));
    assertTrue(inplaceFirst.equals(inplaceSecond));
  }

  @Test
  public void usedInGenruleCommand() throws IOException {
    assumeTrue(luaDevel);
    workspace.writeContentsToPath("require 'os'; io.stdout:write('okay')", "simple.lua");
    Path output = workspace.buildAndReturnOutput("//:genrule");
    assertEquals("okay", workspace.getFileContents(output));
  }

  private LuaBuckConfig getLuaBuckConfig() throws IOException {
    Config rawConfig = Configs.createDefaultConfig(tmp.getRoot());
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(ImmutableMap.of())
            .setSections(rawConfig.getRawConfig())
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()))
            .build();
    return new LuaBuckConfig(buckConfig, new FakeExecutableFinder(ImmutableList.of()));
  }
}
