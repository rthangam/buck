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

package com.facebook.buck.file;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.hash.HashCode;
import java.net.URI;

/**
 * Represents an executable {@link RemoteFile}, which means that it can be invoked using {@code buck
 * run}.
 */
public class RemoteFileBinary extends RemoteFile implements BinaryBuildRule {
  public RemoteFileBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Downloader downloader,
      URI uri,
      HashCode sha1,
      String out,
      Type type) {
    super(buildTarget, projectFilesystem, params, downloader, uri, sha1, out, type);
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder().addArg(SourcePathArg.of(getSourcePathToOutput())).build();
  }
}
