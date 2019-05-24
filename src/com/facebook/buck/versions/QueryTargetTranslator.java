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

package com.facebook.buck.versions;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class QueryTargetTranslator implements TargetTranslator<Query> {

  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;

  public QueryTargetTranslator(UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory) {
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  @Override
  public Class<Query> getTranslatableClass() {
    return Query.class;
  }

  @Override
  public Optional<Query> translateTargets(
      CellPathResolver cellPathResolver,
      String targetBaseName,
      TargetNodeTranslator translator,
      Query query) {

    // Extract all build targets from the original query string.
    ImmutableList<BuildTarget> targets;
    try {
      targets =
          QueryUtils.extractBuildTargets(cellPathResolver, targetBaseName, query)
              .collect(ImmutableList.toImmutableList());
    } catch (QueryException e) {
      throw new RuntimeException("Error parsing/executing query from deps", e);
    }

    // If there's no targets, bail early.
    if (targets.isEmpty()) {
      return Optional.empty();
    }

    // A pattern matching all of the build targets in the query string.
    Pattern targetsPattern =
        Pattern.compile(
            targets.stream()
                .map(Object::toString)
                .map(Pattern::quote)
                .collect(Collectors.joining("|")));

    // Build a new query string from the original by translating all build targets.
    String queryString = query.getQuery();
    Matcher matcher = targetsPattern.matcher(queryString);
    StringBuilder builder = new StringBuilder();
    int lastEnd = 0;
    while (matcher.find()) {
      builder.append(queryString, lastEnd, matcher.start());
      BuildTarget target =
          unconfiguredBuildTargetFactory
              .createForBaseName(cellPathResolver, targetBaseName, matcher.group())
              .configure(query.getTargetConfiguration());
      Optional<BuildTarget> translated =
          translator.translate(cellPathResolver, targetBaseName, target);
      builder.append(translated.orElse(target).getFullyQualifiedName());
      lastEnd = matcher.end();
    }
    builder.append(queryString, lastEnd, queryString.length());
    String newQuery = builder.toString();

    return queryString.equals(newQuery)
        ? Optional.empty()
        : Optional.of(
            Query.of(
                newQuery,
                query.getTargetConfiguration(),
                query.getBaseName(),
                query.getResolvedQuery()));
  }
}
