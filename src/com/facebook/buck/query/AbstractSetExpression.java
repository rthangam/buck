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

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.query;

import com.facebook.buck.core.model.QueryTarget;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

/**
 * A set(word, ..., word) expression, which computes the union of zero or more target patterns
 * separated by whitespace. This is intended to support the use-case in which a set of labels
 * written to a file by a previous query expression can be modified externally, then used as input
 * to another query, like so:
 *
 * <pre>
 * % buck query 'allpaths(foo, bar)' | grep ... | sed ... | awk ... >file
 * % buck query "kind(qux_library, set($(<file)))"
 * </pre>
 *
 * <p>The grammar currently restricts the operands of set() to being zero or more words (target
 * patterns), with no intervening punctuation. In principle this could be extended to arbitrary
 * expressions without grammatical ambiguity, but this seems excessively general for now.
 *
 * <pre>expr ::= SET '(' WORD * ')'</pre>
 */
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractSetExpression<NODE_TYPE> extends QueryExpression<NODE_TYPE> {
  abstract ImmutableList<TargetLiteral<NODE_TYPE>> getWords();

  @Override
  <OUTPUT_TYPE extends QueryTarget> ImmutableSet<OUTPUT_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator, QueryEnvironment<NODE_TYPE> env) throws QueryException {
    ImmutableSet.Builder<OUTPUT_TYPE> result = new ImmutableSet.Builder<>();
    for (TargetLiteral<NODE_TYPE> expr : getWords()) {
      result.addAll(evaluator.eval(expr, env));
    }
    return result.build();
  }

  @Override
  public void traverse(QueryExpression.Visitor<NODE_TYPE> visitor) {
    if (visitor.visit(this) == VisitResult.CONTINUE) {
      for (TargetLiteral<NODE_TYPE> word : getWords()) {
        word.traverse(visitor);
      }
    }
  }

  @Override
  public String toString() {
    return "set(" + Joiner.on(' ').join(getWords()) + ")";
  }
}
