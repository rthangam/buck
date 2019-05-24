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
package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.environment.Platform;

/**
 * Expands $(env XYZ) to use the appropriate shell expansion for the current platform. It does not
 * expand the value of the environment variable in place. Rather, the intention is for the variable
 * to be interpreted when a shell command is invoked.
 */
public class EnvironmentVariableMacroExpander
    extends AbstractMacroExpanderWithoutPrecomputedWork<EnvMacro> {

  private final Platform platform;

  public EnvironmentVariableMacroExpander(Platform platform) {
    this.platform = platform;
  }

  @Override
  public Class<EnvMacro> getInputClass() {
    return EnvMacro.class;
  }

  @Override
  public StringArg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      EnvMacro envMacro) {
    if (platform == Platform.WINDOWS) {
      String var = "pwd".equalsIgnoreCase(envMacro.getVar()) ? "cd" : envMacro.getVar();
      return StringArg.of("%" + var + "%");
    } else {
      return StringArg.of("${" + envMacro.getVar() + "}");
    }
  }
}
