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

package com.facebook.buck.features.go;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class GoToolchainFactory implements ToolchainFactory<GoToolchain> {

  @Override
  public Optional<GoToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    UnresolvedCxxPlatform defaultCxxPlatform =
        cxxPlatformsProviderFactory.getDefaultUnresolvedCxxPlatform();
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProviderFactory.getUnresolvedCxxPlatforms();

    GoPlatformFactory platformFactory =
        GoPlatformFactory.of(
            context.getBuckConfig(),
            context.getProcessExecutor(),
            context.getExecutableFinder(),
            cxxPlatforms,
            defaultCxxPlatform);

    FlavorDomain<GoPlatform> goPlatforms =
        FlavorDomain.from(
            "Go Platforms",
            ImmutableList.<GoPlatform>builder()
                // Add the default platform.
                .add(platformFactory.getPlatform(GoBuckConfig.SECTION, DefaultCxxPlatforms.FLAVOR))
                // Add custom platforms.
                .addAll(
                    context.getBuckConfig().getSections().stream()
                        .flatMap(
                            section ->
                                RichStream.from(
                                    MoreStrings.stripPrefix(section, GoBuckConfig.SECTION + "#")
                                        .map(
                                            name ->
                                                platformFactory.getPlatform(
                                                    section, InternalFlavor.of(name)))))
                        .collect(ImmutableList.toImmutableList()))
                .build());
    GoBuckConfig goBuckConfig = new GoBuckConfig(context.getBuckConfig());
    GoPlatform defaultGoPlatform =
        goPlatforms.getValue(
            goBuckConfig
                .getDefaultPlatform()
                .<Flavor>map(InternalFlavor::of)
                .orElse(defaultCxxPlatform.getFlavor()));

    return Optional.of(GoToolchain.of(goPlatforms, defaultGoPlatform));
  }
}
