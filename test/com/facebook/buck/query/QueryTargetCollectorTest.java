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

package com.facebook.buck.query;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.QueryTarget;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.query.GraphEnhancementQueryEnvironment;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class QueryTargetCollectorTest {
  private static Path ROOT = Paths.get("/fake/cell/root");
  private static String baseName = "//app";
  private QueryEnvironment env =
      new GraphEnhancementQueryEnvironment(
          Optional.empty(),
          Optional.empty(),
          new DefaultTypeCoercerFactory(),
          DefaultCellPathResolver.of(ROOT, ImmutableMap.of()),
          new ParsingUnconfiguredBuildTargetViewFactory(),
          baseName,
          ImmutableSet.of(),
          EmptyTargetConfiguration.INSTANCE);
  private QueryTargetCollector<QueryBuildTarget> collector;

  @Before
  public void setUp() {
    collector = new QueryTargetCollector(env);
  }

  @Test
  public void singleLiteral() {
    literal("foo").traverse(collector);
    assertThat(collector.getTargets(), Matchers.contains(target("foo")));
  }

  @Test
  public void targetSet() {
    ImmutableSet<QueryTarget> targets =
        ImmutableSet.of(target("foo"), target("bar"), target("baz"));
    TargetSetExpression.<QueryBuildTarget>of(targets).traverse(collector);
    assertThat(collector.getTargets(), Matchers.equalTo(targets));
  }

  @Test
  public void emptySet() {
    SetExpression.of(ImmutableList.<TargetLiteral<QueryBuildTarget>>of()).traverse(collector);
    assertThat(collector.getTargets(), Matchers.empty());
  }

  @Test
  public void singletonSet() {
    SetExpression.of(ImmutableList.of(literal("foo"))).traverse(collector);
    assertThat(collector.getTargets(), Matchers.contains(target("foo")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void complexExpression() {
    ImmutableList<Argument<QueryBuildTarget>> args =
        ImmutableList.of(
            (Argument<QueryBuildTarget>)
                Argument.of(SetExpression.of(ImmutableList.of(literal("foo"), literal("bar")))),
            (Argument<QueryBuildTarget>) Argument.of(2));
    BinaryOperatorExpression.of(
            AbstractBinaryOperatorExpression.Operator.UNION,
            ImmutableList.of(
                new ImmutableFunctionExpression<>(new DepsFunction(), args),
                TargetSetExpression.of(ImmutableSet.of(target("bar"), target("baz")))))
        .traverse(collector);
    assertThat(
        collector.getTargets(),
        Matchers.containsInAnyOrder(target("foo"), target("bar"), target("baz")));
  }

  private static TargetLiteral literal(String shortName) {
    return TargetLiteral.of(baseName + ":" + shortName);
  }

  private static QueryTarget target(String shortName) {
    return QueryBuildTarget.of(BuildTargetFactory.newInstance(ROOT, baseName, shortName));
  }
}
