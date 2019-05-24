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

import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CxxCompilationDatabaseIntegrationTest {

  private static final String COMPILER_PATH;
  private static final ImmutableList<String> COMPILER_SPECIFIC_FLAGS =
      Platform.detect() == Platform.MACOS
          ? ImmutableList.of("-Xclang", "-fdebug-compilation-dir", "-Xclang", ".")
          : ImmutableList.of();
  private static final ImmutableList<String> MORE_COMPILER_SPECIFIC_FLAGS =
      Platform.detect() == Platform.LINUX
          ? ImmutableList.of("-gno-record-gcc-switches")
          : ImmutableList.of();
  private static final boolean PREPROCESSOR_SUPPORTS_HEADER_MAPS =
      Platform.detect() == Platform.MACOS;

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  static {
    String executable = Platform.detect() == Platform.MACOS ? "clang++" : "g++";
    COMPILER_PATH =
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get(executable), EnvVariablesProvider.getSystemEnv())
            .orElse(Paths.get("/usr/bin/", executable))
            .toString();
  }

  @Before
  public void initializeWorkspace() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "compilation_database", tmp);
    workspace.setUp();
    // cxx_test requires gtest_dep to be set
    workspace.writeContentsToPath("[cxx]\ngtest_dep = //:fake-gtest", ".buckconfig");
  }

  @Test
  public void binaryWithDependenciesCompilationDatabase() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//:binary_with_dep#compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    Path rootPath = tmp.getRoot();
    assertEquals(
        BuildTargetPaths.getGenPath(filesystem, target, "__%s/compile_commands.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withFlavors(
                InternalFlavor.of("default"), CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    assertTrue(Files.exists(rootPath.resolve(binaryHeaderSymlinkTreeFolder)));

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:library_with_header");
    Path libraryExportedHeaderSymlinkTreeFolder =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            filesystem,
            libraryTarget,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    // Verify that symlink folders for headers are created and header file is linked.
    assertTrue(Files.exists(rootPath.resolve(libraryExportedHeaderSymlinkTreeFolder)));
    assertTrue(Files.exists(rootPath.resolve(libraryExportedHeaderSymlinkTreeFolder + "/bar.h")));

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    String path = "foo.cpp";
    BuildTarget compilationTarget =
        target.withFlavors(
            InternalFlavor.of("default"), InternalFlavor.of("compile-" + sanitize("foo.cpp.o")));
    Map<String, String> prefixMap = new TreeMap<>(Comparator.comparingInt(String::length));
    prefixMap.put(rootPath.toString(), ".");
    if (Platform.detect() == Platform.MACOS) {
      prefixMap.put(libraryExportedHeaderSymlinkTreeFolder + "/", "");
    }
    assertHasEntry(
        fileToEntry,
        path,
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-x")
            .add("c++")
            .add("-I")
            .add(headerSymlinkTreePath(binaryHeaderSymlinkTreeFolder).toString())
            .add("-I")
            .add(headerSymlinkTreePath(libraryExportedHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .addAll(
                prefixMap.entrySet().stream()
                    .map(e -> String.format("-fdebug-prefix-map=%s=%s", e.getKey(), e.getValue()))
                    .collect(Collectors.toList()))
            .addAll(MORE_COMPILER_SPECIFIC_FLAGS)
            .add("-o")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/foo.cpp.o")
                    .toString())
            .add("-c")
            .add("-MD")
            .add("-MF")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/foo.cpp.o.dep")
                    .toString())
            .add(Paths.get(path).toString())
            .build());
  }

  @Test
  public void libraryCompilationDatabase() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRoot();
    assertEquals(
        BuildTargetPaths.getGenPath(filesystem, target, "__%s/compile_commands.json"),
        rootPath.relativize(compilationDatabase));

    Path headerSymlinkTreeFolder =
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withFlavors(
                InternalFlavor.of("default"), CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path exportedHeaderSymlinkTreeFolder =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            filesystem,
            target.withFlavors(),
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());
    System.out.println(workspace.getBuildLog().getAllTargets());

    // Verify that symlink folders for headers are created.
    assertTrue(Files.exists(rootPath.resolve(headerSymlinkTreeFolder)));
    assertTrue(Files.exists(rootPath.resolve(exportedHeaderSymlinkTreeFolder)));

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    String path = "bar.cpp";
    BuildTarget compilationTarget =
        target.withFlavors(
            InternalFlavor.of("default"),
            InternalFlavor.of("compile-pic-" + sanitize("bar.cpp.o")));
    Map<String, String> prefixMap =
        new TreeMap<>(
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
    prefixMap.put(rootPath.toString(), ".");
    if (Platform.detect() == Platform.MACOS) {
      // the compilation flags generated compares path length without the ending "/"
      prefixMap.put(headerSymlinkTreeFolder.toString(), "");
      prefixMap.put(exportedHeaderSymlinkTreeFolder.toString(), "");
    }
    assertHasEntry(
        fileToEntry,
        path,
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-x")
            .add("c++")
            .add("-fPIC")
            .add("-fPIC")
            .add("-I")
            .add(headerSymlinkTreePath(headerSymlinkTreeFolder).toString())
            .add("-I")
            .add(headerSymlinkTreePath(exportedHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .addAll(
                prefixMap.entrySet().stream()
                    .map(
                        e ->
                            String.format(
                                "-fdebug-prefix-map=%s=%s",
                                e.getKey() + (e.getValue().isEmpty() ? "/" : ""), e.getValue()))
                    .collect(Collectors.toList()))
            .addAll(MORE_COMPILER_SPECIFIC_FLAGS)
            .add("-o")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/bar.cpp.o")
                    .toString())
            .add("-c")
            .add("-MD")
            .add("-MF")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/bar.cpp.o.dep")
                    .toString())
            .add(Paths.get(path).toString())
            .build());
  }

  @Test
  public void testCompilationDatabase() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//:test#default,compilation-database");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRoot();
    assertEquals(
        BuildTargetPaths.getGenPath(filesystem, target, "__%s/compile_commands.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withFlavors(
                InternalFlavor.of("default"), CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    String path = "test.cpp";
    BuildTarget compilationTarget =
        target.withFlavors(
            InternalFlavor.of("default"), InternalFlavor.of("compile-" + sanitize("test.cpp.o")));
    assertHasEntry(
        fileToEntry,
        path,
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-x")
            .add("c++")
            .add("-I")
            .add(headerSymlinkTreePath(binaryHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-fdebug-prefix-map=" + rootPath + "=.")
            .addAll(MORE_COMPILER_SPECIFIC_FLAGS)
            .add("-o")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/test.cpp.o")
                    .toString())
            .add("-c")
            .add("-MD")
            .add("-MF")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/test.cpp.o.dep")
                    .toString())
            .add(Paths.get(path).toString())
            .build());
  }

  @Test
  public void testUberCompilationDatabase() throws IOException {
    BuildTarget target =
        BuildTargetFactory.newInstance("//:test#default,uber-compilation-database");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    Path rootPath = tmp.getRoot();
    assertEquals(
        BuildTargetPaths.getGenPath(
            filesystem, target, "uber-compilation-database-%s/compile_commands.json"),
        rootPath.relativize(compilationDatabase));

    Path binaryHeaderSymlinkTreeFolder =
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withFlavors(
                InternalFlavor.of("default"), CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);
    assertEquals(1, fileToEntry.size());
    String path = "test.cpp";
    BuildTarget compilationTarget =
        target.withFlavors(
            InternalFlavor.of("default"), InternalFlavor.of("compile-" + sanitize("test.cpp.o")));
    assertHasEntry(
        fileToEntry,
        path,
        new ImmutableList.Builder<String>()
            .add(COMPILER_PATH)
            .add("-x")
            .add("c++")
            .add("-I")
            .add(headerSymlinkTreePath(binaryHeaderSymlinkTreeFolder).toString())
            .addAll(getExtraFlagsForHeaderMaps(filesystem))
            .addAll(COMPILER_SPECIFIC_FLAGS)
            .add("-fdebug-prefix-map=" + rootPath + "=.")
            .addAll(MORE_COMPILER_SPECIFIC_FLAGS)
            .add("-o")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/test.cpp.o")
                    .toString())
            .add("-c")
            .add("-MD")
            .add("-MF")
            .add(
                BuildTargetPaths.getGenPath(filesystem, compilationTarget, "%s/test.cpp.o.dep")
                    .toString())
            .add(Paths.get(path).toString())
            .build());
  }

  @Test
  public void compilationDatabaseFetchedFromCacheAlsoFetchesSymlinkTreeOrHeaderMap()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // This test only fails if the directory cache is enabled and we don't update
    // the header map/symlink tree correctly when fetching from the cache.
    workspace.enableDirCache();

    addLibraryHeaderFiles(workspace);

    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");

    // Populate the cache with the built rule
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());

    Path headerSymlinkTreeFolder =
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withFlavors(
                InternalFlavor.of("default"), CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
            "%s");
    Path exportedHeaderSymlinkTreeFolder =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            filesystem,
            target.withFlavors(),
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    // Validate the symlink tree/header maps
    verifyHeaders(workspace, headerSymlinkTreeFolder, "bar.h", "baz.h", "blech_private.h");
    verifyHeaders(workspace, exportedHeaderSymlinkTreeFolder, "bar.h", "baz.h");

    // Delete the newly-added files and build again
    Files.delete(workspace.getPath("baz.h"));
    Files.delete(workspace.getPath("blech_private.h"));
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(workspace, headerSymlinkTreeFolder, "bar.h");
    verifyHeaders(workspace, exportedHeaderSymlinkTreeFolder, "bar.h");

    // Restore the headers, build again, and check the symlink tree/header maps
    addLibraryHeaderFiles(workspace);
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(workspace, headerSymlinkTreeFolder, "bar.h", "baz.h", "blech_private.h");
    verifyHeaders(workspace, exportedHeaderSymlinkTreeFolder, "bar.h", "baz.h");
  }

  @Test
  public void compilationDatabaseWithDepsFetchedFromCacheAlsoFetchesSymlinkTreeOrHeaderMapOfDeps()
      throws Exception {
    // Create a new temporary path since this test uses a different testdata directory than the
    // one used in the common setup method.
    tmp.after();
    tmp = new TemporaryPaths();
    tmp.before();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "compilation_database_with_deps", tmp);
    workspace.setUp();
    workspace.writeContentsToPath("[cxx]\ngtest_dep = //:fake-gtest", ".buckconfig");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // This test only fails if the directory cache is enabled and we don't update
    // the header map/symlink tree correctly when fetching from the cache.
    workspace.enableDirCache();

    addDepLibraryHeaderFiles(workspace);

    BuildTarget target =
        BuildTargetFactory.newInstance("//:library_with_header#default,compilation-database");

    // Populate the cache with the built rule
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());

    Path dep1ExportedSymlinkTreeFolder =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            filesystem,
            BuildTargetFactory.newInstance("//dep1:dep1"),
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());
    Path dep2ExportedSymlinkTreeFolder =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            filesystem,
            BuildTargetFactory.newInstance("//dep2:dep2"),
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    // Validate the deps' symlink tree/header maps
    verifyHeaders(workspace, dep1ExportedSymlinkTreeFolder, "dep1/dep1.h", "dep1/dep1_new.h");
    verifyHeaders(workspace, dep2ExportedSymlinkTreeFolder, "dep2/dep2.h", "dep2/dep2_new.h");

    // Delete the newly-added files and build again
    Files.delete(workspace.getPath("dep1/dep1_new.h"));
    Files.delete(workspace.getPath("dep2/dep2_new.h"));
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(workspace, dep1ExportedSymlinkTreeFolder, "dep1/dep1.h");
    verifyHeaders(workspace, dep2ExportedSymlinkTreeFolder, "dep2/dep2.h");

    // Restore the headers, build again, and check the deps' symlink tree/header maps
    addDepLibraryHeaderFiles(workspace);
    workspace.buildAndReturnOutput(target.getFullyQualifiedName());
    verifyHeaders(workspace, dep1ExportedSymlinkTreeFolder, "dep1/dep1.h", "dep1/dep1_new.h");
    verifyHeaders(workspace, dep2ExportedSymlinkTreeFolder, "dep2/dep2.h", "dep2/dep2_new.h");
  }

  @Test
  public void compilationDatabaseWithGeneratedFilesFetchedFromCacheAlsoFetchesGeneratedHeaders()
      throws Exception {
    // Create a new temporary path since this test uses a different testdata directory than the
    // one used in the common setup method.
    tmp.after();
    tmp = new TemporaryPaths();
    tmp.before();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "compilation_database_with_generated_files", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget target =
        BuildTargetFactory.newInstance("//:binary_with_dep#default,compilation-database");

    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget headerTarget = BuildTargetFactory.newInstance("//dep1:header");
    Path header = workspace.getPath(BuildTargetPaths.getGenPath(filesystem, headerTarget, "%s"));
    assertThat(Files.exists(header), is(true));
  }

  @Test
  public void compilationDatabaseWithGeneratedFilesFetchedFromCacheAlsoFetchesGeneratedSources()
      throws Exception {
    // Create a new temporary path since this test uses a different testdata directory than the
    // one used in the common setup method.
    tmp.after();
    tmp = new TemporaryPaths();
    tmp.before();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "compilation_database_with_generated_files", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget target = BuildTargetFactory.newInstance("//dep1:dep1#default,compilation-database");

    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();

    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget sourceTarget = BuildTargetFactory.newInstance("//dep1:source");
    Path source = workspace.getPath(BuildTargetPaths.getGenPath(filesystem, sourceTarget, "%s"));
    assertThat(Files.exists(source), is(true));
  }

  @Test
  public void testPlatformDepsAddedToCompilationDatabase() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_library_platform_deps", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "build", "--config", "cxx.default_platform=platform", "//:echo#compilation-database");

    result.assertSuccess();
  }

  private void addLibraryHeaderFiles(ProjectWorkspace workspace) throws IOException {
    // These header files are included in //:library_with_header via a glob
    workspace.writeContentsToPath("// Hello world\n", "baz.h");
    workspace.writeContentsToPath("// Hello private world\n", "blech_private.h");
  }

  private void addDepLibraryHeaderFiles(ProjectWorkspace workspace) throws IOException {
    workspace.writeContentsToPath("// Hello dep1 world\n", "dep1/dep1_new.h");
    workspace.writeContentsToPath("// Hello dep2 world\n", "dep2/dep2_new.h");
  }

  private void verifyHeaders(ProjectWorkspace projectWorkspace, Path path, String... headers)
      throws IOException {
    Path resolvedPath = headerSymlinkTreePath(path);
    if (PREPROCESSOR_SUPPORTS_HEADER_MAPS) {
      List<String> presentHeaders = new ArrayList<>();
      HeaderMap headerMap = HeaderMap.loadFromFile(projectWorkspace.getPath(resolvedPath).toFile());
      headerMap.visit((str, prefix, suffix) -> presentHeaders.add(str));
      assertThat(presentHeaders, containsInAnyOrder(headers));
    } else {
      for (String header : headers) {
        assertThat(Files.exists(projectWorkspace.getPath(resolvedPath.resolve(header))), is(true));
      }
    }
  }

  private Path headerSymlinkTreePath(Path path) {
    if (PREPROCESSOR_SUPPORTS_HEADER_MAPS) {
      return path.resolveSibling(String.format("%s.hmap", path.getFileName()));
    } else {
      return path;
    }
  }

  private void assertHasEntry(
      Map<String, CxxCompilationDatabaseEntry> fileToEntry, String fileName, List<String> command)
      throws IOException {
    String key = tmp.getRoot().toRealPath().resolve(fileName).toString();
    CxxCompilationDatabaseEntry entry = fileToEntry.get(key);
    assertNotNull("There should be an entry for " + key + ".", entry);
    assertThat(command, equalTo(entry.getArguments()));
  }

  private ImmutableList<String> getExtraFlagsForHeaderMaps(ProjectFilesystem filesystem) {
    // This works around OS X being amusing about the location of temp directories.
    return PREPROCESSOR_SUPPORTS_HEADER_MAPS
        ? ImmutableList.of("-I", filesystem.getBuckPaths().getBuckOut().toString())
        : ImmutableList.of();
  }
}
