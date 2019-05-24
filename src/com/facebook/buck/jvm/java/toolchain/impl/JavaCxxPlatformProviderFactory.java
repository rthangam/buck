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

package com.facebook.buck.jvm.java.toolchain.impl;

import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import java.util.Optional;

public class JavaCxxPlatformProviderFactory implements ToolchainFactory<JavaCxxPlatformProvider> {

  @Override
  public Optional<JavaCxxPlatformProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    JavaBuckConfig javaConfig = context.getBuckConfig().getView(JavaBuckConfig.class);
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    UnresolvedCxxPlatform defaultJavaCxxPlatform =
        javaConfig
            .getDefaultCxxPlatform()
            .map(InternalFlavor::of)
            .map(cxxPlatformsProvider.getUnresolvedCxxPlatforms()::getValue)
            .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform());
    return Optional.of(JavaCxxPlatformProvider.of(defaultJavaCxxPlatform));
  }
}
