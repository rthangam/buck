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

package com.facebook.buck.cli;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableMap;
import java.io.PrintStream;
import java.util.function.Consumer;
import org.kohsuke.args4j.Argument;

/** Prints a requested rule type as a Python function with all supported attributes. */
public class AuditRuleTypeCommand extends AbstractCommand {

  private static final String INDENT = "    ";

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) {
    KnownRuleTypes knownRuleTypes = params.getKnownRuleTypesProvider().get(params.getCell());
    RuleType buildRuleType = knownRuleTypes.getRuleType(ruleName);
    BaseDescription<?> description = knownRuleTypes.getDescription(buildRuleType);
    printPythonFunction(params.getConsole(), description, params.getTypeCoercerFactory());
    return ExitCode.SUCCESS;
  }

  @Argument(required = true, metaVar = "RULE_NAME")
  private String ruleName;

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Print requested build rule type as a Python function signature.";
  }

  static void printPythonFunction(
      Console console, BaseDescription<?> description, TypeCoercerFactory typeCoercerFactory) {
    PrintStream printStream = console.getStdOut();
    ImmutableMap<String, ParamInfo> allParamInfo =
        CoercedTypeCache.INSTANCE.getAllParamInfo(
            typeCoercerFactory, description.getConstructorArgType());
    String name = DescriptionCache.getRuleType(description).getName();
    printStream.println("def " + name + " (");
    allParamInfo.values().stream()
        .filter(param -> !param.isOptional())
        .sorted()
        .forEach(formatPythonFunction(printStream));
    allParamInfo.values().stream()
        .filter(ParamInfo::isOptional)
        .sorted()
        .forEach(formatPythonFunction(printStream));
    printStream.println("):");
    printStream.println(INDENT + "...");
  }

  private static Consumer<ParamInfo> formatPythonFunction(PrintStream printStream) {
    return param ->
        printStream.println(
            INDENT + param.getPythonName() + (param.isOptional() ? " = None" : "") + ",");
  }
}
