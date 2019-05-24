/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.cxx.toolchain.ToolType;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** High level tests for Precompiled header feature. */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class PrecompiledHeaderFeatureTest {

  /** Tests that PCH is only used when a preprocessor is declared to use PCH. */
  @RunWith(Parameterized.class)
  public static class OnlyPrecompilePrefixHeaderIfToolchainIsSupported {

    @Parameterized.Parameter(0)
    public CxxToolProvider.Type toolType;

    @Parameterized.Parameter(1)
    public boolean pchEnabled;

    @Parameterized.Parameter(2)
    public boolean expectUsingPch;

    private CxxPlatform getPlatform() {
      return buildPlatform(toolType, pchEnabled);
    }

    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> data() {
      return Arrays.asList(
          new Object[][] {
            {CxxToolProvider.Type.CLANG, true, true},
            {CxxToolProvider.Type.CLANG, false, false},
            {CxxToolProvider.Type.GCC, true, true},
            {CxxToolProvider.Type.GCC, false, false},
            // TODO(steveo): add WINDOWS
          });
    }

    @Test
    public void test() {
      String headerFilename = "foo.h";
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      CxxPreprocessAndCompile rule =
          preconfiguredSourceRuleFactoryBuilder(graphBuilder)
              .setCxxPlatform(getPlatform())
              .setCxxBuckConfig(buildConfig(pchEnabled))
              .setPrefixHeader(FakeSourcePath.of(headerFilename))
              .setPrecompiledHeader(Optional.empty())
              .build()
              .requirePreprocessAndCompileBuildRule(
                  "foo.c", preconfiguredCxxSourceBuilder().build());
      boolean hasPchFlag =
          commandLineContainsPchFlag(
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()),
              rule,
              toolType,
              headerFilename);
      boolean hasPrefixFlag =
          commandLineContainsPrefixFlag(
              FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver()),
              rule,
              toolType,
              headerFilename);
      assertNotEquals(
          "should use either prefix header flag OR precompiled header flag; one but not both:"
              + " toolType:"
              + toolType
              + " pchEnabled:"
              + pchEnabled
              + ";"
              + " hasPrefixFlag:"
              + hasPrefixFlag
              + " hasPchFlag:"
              + hasPchFlag,
          hasPrefixFlag,
          hasPchFlag);
      assertEquals(
          "should precompile prefix header IFF supported and enabled:"
              + " toolType:"
              + toolType
              + " pchEnabled:"
              + pchEnabled
              + ";"
              + " expect:"
              + expectUsingPch,
          expectUsingPch,
          hasPchFlag);
    }
  }

  public static class TestSupportConditions {
    @Test
    public void rejectPchParameterIfSourceTypeDoesntSupportPch() {
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      CxxPlatform platform =
          PLATFORM_SUPPORTING_PCH.withCompilerDebugPathSanitizer(
              new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of()));
      CxxBuckConfig config = buildConfig(/* pchEnabled */ true);
      CxxSourceRuleFactory factory =
          preconfiguredSourceRuleFactoryBuilder(graphBuilder)
              .setCxxPlatform(platform)
              .setPrefixHeader(FakeSourcePath.of(("foo.pch")))
              .setCxxBuckConfig(config)
              .build();

      for (AbstractCxxSource.Type sourceType : AbstractCxxSource.Type.values()) {
        if (!sourceType.isPreprocessable()) {
          // Need a preprocessor object if we want to test for PCH'ability.
          continue;
        }

        switch (sourceType) {
          case ASM_WITH_CPP:
          case ASM:
          case CUDA:
            // The default platform we're testing with doesn't include preprocessors for these.
            continue;

            // $CASES-OMITTED$
          default:
            assertEquals(
                sourceType.getPrecompiledHeaderLanguage().isPresent(),
                factory.canUsePrecompiledHeaders(sourceType));
        }
      }
    }
  }

  public static class PrecompiledHeaderBuildTargetGeneration {
    @Test
    public void buildTargetShouldDeriveFromSanitizedFlags() {
      class TestData {
        public CxxPrecompiledHeader generate(Path from) {
          ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
          CxxSourceRuleFactory factory =
              preconfiguredSourceRuleFactoryBuilder(graphBuilder)
                  .setCxxPlatform(
                      PLATFORM_SUPPORTING_PCH.withCompilerDebugPathSanitizer(
                          new PrefixMapDebugPathSanitizer(".", ImmutableBiMap.of(from, "melon"))))
                  .setPrefixHeader(FakeSourcePath.of(("foo.pch")))
                  .setCxxBuckConfig(buildConfig(/* pchEnabled */ true))
                  .build();
          BuildRule rule =
              factory.requirePreprocessAndCompileBuildRule(
                  "foo.c", preconfiguredCxxSourceBuilder().addFlags("-I", from.toString()).build());
          return FluentIterable.from(rule.getBuildDeps())
              .filter(CxxPrecompiledHeader.class)
              .first()
              .get();
        }
      }
      TestData testData = new TestData();

      Path root =
          Objects.requireNonNull(
              Iterables.getFirst(
                  FileSystems.getDefault().getRootDirectories(), Paths.get(File.separator)));
      CxxPrecompiledHeader firstRule = testData.generate(root.resolve("first"));
      CxxPrecompiledHeader secondRule = testData.generate(root.resolve("second"));
      assertEquals(
          "Build target flavor generator should normalize away the absolute paths",
          firstRule.getBuildTarget(),
          secondRule.getBuildTarget());
    }

    @Test
    public void buildTargetShouldVaryWithCompilerFlags() {
      class TestData {
        public CxxPrecompiledHeader generate(Iterable<String> flags) {
          ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
          CxxSourceRuleFactory factory =
              preconfiguredSourceRuleFactoryBuilder(graphBuilder)
                  .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
                  .setCxxBuckConfig(buildConfig(/* pchEnabled */ true))
                  .putAllCompilerFlags(CxxSource.Type.C_CPP_OUTPUT, StringArg.from(flags))
                  .setPrefixHeader(FakeSourcePath.of(("foo.h")))
                  .build();
          BuildRule rule =
              factory.requirePreprocessAndCompileBuildRule(
                  "foo.c", preconfiguredCxxSourceBuilder().build());
          return FluentIterable.from(rule.getBuildDeps())
              .filter(CxxPrecompiledHeader.class)
              .first()
              .get();
        }
      }
      TestData testData = new TestData();

      CxxPrecompiledHeader firstRule =
          testData.generate(ImmutableList.of("-target=x86_64-apple-darwin-macho"));
      CxxPrecompiledHeader secondRule =
          testData.generate(ImmutableList.of("-target=armv7-apple-watchos-macho"));
      assertNotEquals(
          "Build target flavor generator should account for compiler flags",
          firstRule.getBuildTarget(),
          secondRule.getBuildTarget());
    }

    @Test
    public void buildTargetShouldVaryWithPreprocessorFlags() {
      class TestData {
        public CxxPrecompiledHeader generate(String flags) {
          ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
          CxxSourceRuleFactory factory =
              preconfiguredSourceRuleFactoryBuilder(graphBuilder)
                  .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
                  .setCxxBuckConfig(buildConfig(/* pchEnabled */ true))
                  .setCxxPreprocessorInput(
                      ImmutableList.of(
                          CxxPreprocessorInput.builder()
                              .setPreprocessorFlags(
                                  ImmutableMultimap.of(CxxSource.Type.C, StringArg.of(flags)))
                              .build()))
                  .setPrefixHeader(FakeSourcePath.of(("foo.h")))
                  .build();
          BuildRule rule =
              factory.requirePreprocessAndCompileBuildRule(
                  "foo.c", preconfiguredCxxSourceBuilder().build());
          return FluentIterable.from(rule.getBuildDeps())
              .filter(CxxPrecompiledHeader.class)
              .first()
              .get();
        }
      }
      TestData testData = new TestData();

      CxxPrecompiledHeader firstRule = testData.generate("-DNDEBUG");
      CxxPrecompiledHeader secondRule = testData.generate("-UNDEBUG");
      assertNotEquals(
          "Build target flavor generator should account for preprocessor flags",
          firstRule.getBuildTarget(),
          secondRule.getBuildTarget());
    }

    @Test
    public void noPrecompiledHeaderForAsmInputs() {
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
      CxxSourceRuleFactory factory =
          preconfiguredSourceRuleFactoryBuilder(graphBuilder)
              .setCxxPlatform(PLATFORM_SUPPORTING_PCH)
              .setCxxBuckConfig(buildConfig(/* pchEnabled */ true))
              .setPrefixHeader(FakeSourcePath.of(("foo.h")))
              .build();
      BuildRule rule =
          factory.requireCompileBuildRule(
              "foo.S",
              CxxSource.builder()
                  .setType(CxxSource.Type.ASSEMBLER)
                  .setPath(FakeSourcePath.of("foo.S"))
                  .build());
      CxxPrecompiledHeader headerRule =
          FluentIterable.from(rule.getBuildDeps())
              .filter(CxxPrecompiledHeader.class)
              .first()
              .orNull();
      assertNull("Asm files shouldn't support precompiled headers", headerRule);
    }
  }

  // Helpers and defaults

  /**
   * Checks if the command line generated for the build rule contains the pch-inclusion directive.
   *
   * <p>This serves as an indicator that the file is being compiled with PCH enabled.
   */
  private static boolean commandLineContainsPchFlag(
      BuildContext context,
      CxxPreprocessAndCompile rule,
      CxxToolProvider.Type toolType,
      String headerFilename) {

    ImmutableList<String> flags = rule.makeMainStep(context, false).getCommand();

    switch (toolType) {
      case CLANG:
        // Clang uses "-include-pch somefilename.h.gch"
        for (int i = 0; i + 1 < flags.size(); i++) {
          if (flags.get(i).equals("-include-pch") && flags.get(i + 1).endsWith(".h.gch")) {
            return true;
          }
        }
        break;

      case GCC:
        // For GCC we'd use: "-include sometargetname#someflavor-cxx-hexdigitsofhash.h",
        // i.e., it's the "-include" flag like in the prefix header case, but auto-gen-filename.
        for (int i = 0; i + 1 < flags.size(); i++) {
          if (flags.get(i).equals("-include") && !flags.get(i + 1).endsWith("/" + headerFilename)) {
            return true;
          }
        }
        break;

      case WINDOWS:
        // TODO(steveo): windows support in the near future.
        // (This case is not hit; parameters at top of this test class don't include WINDOWS.)
        throw new IllegalStateException();
    }

    return false;
  }

  private static boolean commandLineContainsPrefixFlag(
      BuildContext context,
      CxxPreprocessAndCompile rule,
      CxxToolProvider.Type toolType,
      String headerFilename) {

    ImmutableList<String> flags = rule.makeMainStep(context, false).getCommand();

    switch (toolType) {
      case CLANG:
      case GCC:
        // Clang and GCC use "-include somefilename.h".
        // GCC uses "-include" for precompiled headers as well, but in the GCC PCH case, we'll
        // pass a PCH filename that's auto-generated from a target name + flavors + hash chars
        // and other weird stuff.  Here we'll be expecting the original header filename.
        for (int i = 0; i + 1 < flags.size(); i++) {
          if (flags.get(i).equals("-include") && flags.get(i + 1).endsWith("/" + headerFilename)) {
            return true;
          }
        }
        break;

      case WINDOWS:
        // TODO(steveo): windows support in the near future.
        // (This case is not hit; parameters at top of this test class don't include WINDOWS.)
        throw new IllegalStateException();
    }

    return false;
  }

  /**
   * Configures a CxxSourceRuleFactory.Builder with some sane defaults for PCH tests. Note: doesn't
   * call "setPrefixHeader", which actually sets the PCH parameters; caller needs to do that in
   * their various tests.
   */
  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder(
      String targetPath, ActionGraphBuilder graphBuilder) {
    BuildTarget target = BuildTargetFactory.newInstance(targetPath);
    return CxxSourceRuleFactory.builder()
        .setProjectFilesystem(new FakeProjectFilesystem())
        .setBaseBuildTarget(target)
        .setActionGraphBuilder(graphBuilder)
        .setPathResolver(graphBuilder.getSourcePathResolver())
        .setPicType(PicType.PDC);
  }

  private static CxxSourceRuleFactory.Builder preconfiguredSourceRuleFactoryBuilder(
      ActionGraphBuilder graphBuilder) {
    return preconfiguredSourceRuleFactoryBuilder("//foo:bar", graphBuilder);
  }

  /** Configures a CxxSource.Builder representing a C source file. */
  private static CxxSource.Builder preconfiguredCxxSourceBuilder() {
    return CxxSource.builder().setType(CxxSource.Type.C).setPath(FakeSourcePath.of("foo.c"));
  }

  private static CxxBuckConfig buildConfig(boolean pchEnabled) {
    return new CxxBuckConfig(
        FakeBuckConfig.builder().setSections("[cxx]", "pch_enabled=" + pchEnabled).build());
  }

  /**
   * Build a CxxPlatform for given preprocessor type.
   *
   * @return CxxPlatform containing a config which enables pch, and a preprocessor which may or may
   *     not support PCH.
   */
  private static CxxPlatform buildPlatform(CxxToolProvider.Type type, boolean pchEnabled) {
    return CxxPlatformUtils.build(buildConfig(pchEnabled))
        .withCpp(
            new PreprocessorProvider(
                new ConstantToolProvider(
                    new HashedFileTool(
                        PathSourcePath.of(
                            new FakeProjectFilesystem(), Paths.get("/usr/bin/foopp")))),
                type,
                ToolType.CPP));
  }

  private static final CxxPlatform PLATFORM_SUPPORTING_PCH =
      buildPlatform(CxxToolProvider.Type.CLANG, true);
}
