/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.event.FlushConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.function.BuckPyFunction;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Evaluates a build file and prints out an equivalent build file with all includes/macros expanded.
 * When complex macros are in play, this helps clarify what the resulting build rule definitions
 * are.
 */
public class AuditRulesCommand extends AbstractCommand {

  /** Indent to use in generated build file. */
  private static final String INDENT = "  ";

  /** Properties that should be listed last in the declaration of a build rule. */
  private static final ImmutableSet<String> LAST_PROPERTIES = ImmutableSet.of("deps", "visibility");

  @Option(
      name = "--type",
      aliases = {"-t"},
      usage = "The types of rule to filter by")
  @Nullable
  private List<String> types = null;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  public ImmutableSet<String> getTypes() {
    return types == null ? ImmutableSet.of() : ImmutableSet.copyOf(types);
  }

  @Override
  public String getShortDescription() {
    return "List build rule definitions resulting from expanding macros.";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    ProjectFilesystem projectFilesystem = params.getCell().getFilesystem();
    try (ProjectBuildFileParser parser =
        new DefaultProjectBuildFileParserFactory(
                new DefaultTypeCoercerFactory(),
                params.getConsole(),
                new ParserPythonInterpreterProvider(
                    params.getCell().getBuckConfig(), params.getExecutableFinder()),
                params.getKnownRuleTypesProvider(),
                params.getManifestServiceSupplier(),
                params.getFileHashCache())
            .createBuildFileParser(
                params.getBuckEventBus(), params.getCell(), params.getWatchman(), false)) {
      /*
       * The super console does a bunch of rewriting over the top of the console such that
       * simultaneously writing to stdout and stderr in an interactive session is problematic.
       * (Overwritten characters, lines never showing up, etc). As such, writing to stdout directly
       * stops superconsole rendering (no errors appear). Because of all of this, we need to
       * just buffer the output and print it to stdout at the end fo the run. The downside
       * is that we have to buffer all of the output in memory, and it could potentially be large,
       * however, we'll just have to accept that tradeoff for now to get both error messages
       * from the parser, and the final output
       */

      try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
          PrintStream out = new PrintStream(new BufferedOutputStream(byteOut))) {
        for (String pathToBuildFile : getArguments()) {
          // Print a comment with the path to the build file.
          out.printf("# %s\n\n", pathToBuildFile);

          // Resolve the path specified by the user.
          Path path = Paths.get(pathToBuildFile);
          if (!path.isAbsolute()) {
            Path root = projectFilesystem.getRootPath();
            path = root.resolve(path);
          }

          // Parse the rules from the build file.
          ImmutableMap<String, Map<String, Object>> rawRules =
              parser.getBuildFileManifest(path).getTargets();

          // Format and print the rules from the raw data, filtered by type.
          ImmutableSet<String> types = getTypes();
          Predicate<String> includeType = type -> types.isEmpty() || types.contains(type);
          printRulesToStdout(out, rawRules, includeType);
        }

        // Make sure we tell the event listener to flush, otherwise there is a race condition where
        // the event listener might not have flushed, we dirty the stream, and then it will not
        // render the last frame (see {@link SuperConsoleEventListener})
        params.getBuckEventBus().post(new FlushConsoleEvent());
        out.close();
        params.getConsole().getStdOut().write(byteOut.toByteArray());
      }
    }

    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private void printRulesToStdout(
      PrintStream stdOut,
      ImmutableMap<String, Map<String, Object>> rawRules,
      Predicate<String> includeType) {
    rawRules.entrySet().stream()
        .filter(
            rawRule -> {
              String type = (String) rawRule.getValue().get(BuckPyFunction.TYPE_PROPERTY_NAME);
              return includeType.test(type);
            })
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEach(rawRule -> printRuleAsPythonToStdout(stdOut, rawRule.getValue()));
  }

  private static void printRuleAsPythonToStdout(PrintStream out, Map<String, Object> rawRule) {
    String type = (String) rawRule.get(BuckPyFunction.TYPE_PROPERTY_NAME);
    out.printf("%s(\n", type);

    // The properties in the order they should be displayed for this rule.
    LinkedHashSet<String> properties = new LinkedHashSet<>();

    // Always display the "name" property first.
    properties.add("name");

    // Add the properties specific to the rule.
    SortedSet<String> customProperties = new TreeSet<>();
    for (String key : rawRule.keySet()) {
      // Ignore keys that start with "buck.".
      if (!(key.startsWith(BuckPyFunction.INTERNAL_PROPERTY_NAME_PREFIX)
          || LAST_PROPERTIES.contains(key))) {
        customProperties.add(key);
      }
    }
    properties.addAll(customProperties);

    // Add common properties that should be displayed last.
    properties.addAll(Sets.intersection(LAST_PROPERTIES, rawRule.keySet()));

    // Write out the properties and their corresponding values.
    for (String property : properties) {
      Object rawValue = rawRule.get(property);
      if (!shouldInclude(rawValue)) {
        continue;
      }
      String displayValue = createDisplayString(INDENT, rawValue);
      out.printf("%s%s = %s,\n", INDENT, formatAttribute(property), displayValue);
    }

    // Close the rule definition.
    out.print(")\n\n");
  }

  private static String formatAttribute(String property) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, property);
  }

  private static boolean shouldInclude(@Nullable Object rawValue) {
    return rawValue != null
        && rawValue != Optional.empty()
        && !(rawValue instanceof Collection && ((Collection<?>) rawValue).isEmpty());
  }

  /**
   * @param value a map representing a raw build target.
   * @return a string that represents the Python equivalent of the value.
   */
  @VisibleForTesting
  static String createDisplayString(@Nullable Object value) {
    return createDisplayString("", value);
  }

  private static String createDisplayString(String indent, @Nullable Object value) {
    if (value == null) {
      return "None";
    } else if (value instanceof Boolean) {
      return MoreStrings.capitalize(value.toString());
    } else if (value instanceof String) {
      return Escaper.escapeAsPythonString(value.toString());
    } else if (value instanceof Number) {
      return value.toString();
    } else if (value instanceof List) {
      StringBuilder out = new StringBuilder("[\n");

      String indentPlus1 = indent + INDENT;
      for (Object item : (List<?>) value) {
        out.append(indentPlus1).append(createDisplayString(indentPlus1, item)).append(",\n");
      }

      out.append(indent).append("]");
      return out.toString();
    } else if (value instanceof Map) {
      StringBuilder out = new StringBuilder("{\n");

      String indentPlus1 = indent + INDENT;
      for (Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        out.append(indentPlus1)
            .append(createDisplayString(indentPlus1, entry.getKey()))
            .append(": ")
            .append(createDisplayString(indentPlus1, entry.getValue()))
            .append(",\n");
      }

      out.append(indent).append("}");
      return out.toString();
    } else if (value instanceof ListWithSelects) {
      return value.toString();
    } else {
      throw new IllegalStateException();
    }
  }
}
