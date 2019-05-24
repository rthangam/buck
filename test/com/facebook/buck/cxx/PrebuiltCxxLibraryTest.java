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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class PrebuiltCxxLibraryTest {

  @Test
  public void testGetNativeLinkWithDep() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform platform = CxxPlatformUtils.DEFAULT_PLATFORM;

    GenruleBuilder genSrcBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen_libx"))
            .setOut("libx.a")
            .setCmd("something");
    BuildTarget target = BuildTargetFactory.newInstance("//:x");
    PrebuiltCxxLibraryBuilder builder =
        new PrebuiltCxxLibraryBuilder(target)
            .setStaticLib(DefaultBuildTargetSourcePath.of(genSrcBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(genSrcBuilder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule genSrc = genSrcBuilder.build(graphBuilder, filesystem, targetGraph);
    filesystem.writeContentsToPath(
        "class Test {}",
        graphBuilder.getSourcePathResolver().getAbsolutePath(genSrc.getSourcePathToOutput()));

    PrebuiltCxxLibrary lib =
        (PrebuiltCxxLibrary) builder.build(graphBuilder, filesystem, targetGraph);
    lib.getNativeLinkableInput(
        platform, Linker.LinkableDepType.STATIC, graphBuilder, EmptyTargetConfiguration.INSTANCE);

    FileHashCache originalHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    DefaultRuleKeyFactory factory = new TestDefaultRuleKeyFactory(originalHashCache, graphBuilder);

    RuleKey ruleKey = factory.build(lib);
    assertNotNull(ruleKey);
  }
}
