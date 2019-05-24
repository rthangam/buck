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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ProvidesWorkerTool;
import com.facebook.buck.shell.WorkerTool;
import java.util.function.Consumer;

/** Macro expander for the `$(worker ...)` macro. */
public class WorkerMacroExpander extends BuildTargetMacroExpander<WorkerMacro> {

  @Override
  public Class<WorkerMacro> getInputClass() {
    return WorkerMacro.class;
  }

  protected Tool getTool(BuildRule rule) throws MacroException {
    if (rule instanceof ProvidesWorkerTool) {
      WorkerTool workerTool = ((ProvidesWorkerTool) rule).getWorkerTool();
      return workerTool.getTool();
    }
    throw new MacroException(
        String.format(
            "%s used in worker macro does not correspond to a rule that can provide a worker tool",
            rule.getBuildTarget()));
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, WorkerMacro ignored, BuildRule rule)
      throws MacroException {
    return new WorkerToolArg(getTool(rule));
  }

  private class WorkerToolArg implements Arg {
    @AddToRuleKey private final Tool tool;

    public WorkerToolArg(Tool tool) {
      this.tool = tool;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {}
  }
}
