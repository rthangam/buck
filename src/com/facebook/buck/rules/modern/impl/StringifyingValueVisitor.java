/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.DefaultTargetConfiguration;
import com.facebook.buck.core.model.impl.HostTargetConfiguration;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehaviorTag;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.util.Scope;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** A ValueVisitor that can be used to construct a String representation of an object. */
public class StringifyingValueVisitor implements ValueVisitor<RuntimeException> {
  private StringBuilder builder = new StringBuilder();
  private int indent = 0;

  @Override
  public void visitOutputPath(OutputPath value) {
    append(
        "OutputPath(%s)",
        PathFormatter.pathWithUnixSeparators(OutputPath.internals().getPath(value)));
  }

  @Override
  public void visitSourcePath(SourcePath value) {
    append("SourcePath(%s)", value.toString().replace('\\', '/'));
  }

  @Override
  public <T> void visitField(
      Field field,
      T value,
      ValueTypeInfo<T> valueTypeInfo,
      List<Class<? extends CustomFieldBehaviorTag>> customBehavior) {
    newline();
    append("%s:", field.getName());
    valueTypeInfo.visit(value, this);
  }

  private <T> void visitIterableValues(Iterable<T> value, ValueTypeInfo<T> innerType) {
    for (T e : value) {
      newline();
      innerType.visit(e, this);
    }
  }

  @Override
  public <T> void visitSet(ImmutableSet<T> value, ValueTypeInfo<T> innerType)
      throws RuntimeException {
    container("Set", () -> visitIterableValues(value, innerType));
  }

  @Override
  public <T> void visitSortedSet(ImmutableSortedSet<T> value, ValueTypeInfo<T> innerType) {
    container("SortedSet", () -> visitIterableValues(value, innerType));
  }

  @Override
  public <K, V> void visitMap(
      ImmutableMap<K, V> value, ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType)
      throws RuntimeException {
    container("Map", () -> visitMapValues(value, keyType, valueType));
  }

  @Override
  public <K, V> void visitSortedMap(
      ImmutableSortedMap<K, V> value, ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType) {
    container("SortedMap", () -> visitMapValues(value, keyType, valueType));
  }

  private <K, V> void visitMapValues(
      ImmutableMap<K, V> value, ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType) {
    for (Map.Entry<K, V> e : value.entrySet()) {
      newline();
      container(
          "key",
          () -> {
            newline();
            keyType.visit(e.getKey(), this);
          });
      newline();
      container(
          "value",
          () -> {
            newline();
            valueType.visit(e.getValue(), this);
          });
    }
  }

  @Override
  public <T> void visitList(ImmutableList<T> value, ValueTypeInfo<T> innerType) {
    container("List", () -> visitIterableValues(value, innerType));
  }

  @Override
  public <T> void visitOptional(Optional<T> value, ValueTypeInfo<T> innerType) {
    if (value.isPresent()) {
      container(
          "Optional",
          () -> {
            newline();
            innerType.visit(value.get(), this);
          });
    } else {
      append("Optional.empty()");
    }
  }

  @Override
  public <T> void visitNullable(@Nullable T value, ValueTypeInfo<T> inner) throws RuntimeException {
    if (value == null) {
      append("null");
    } else {
      inner.visit(value, this);
    }
  }

  @Override
  public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo)
      throws RuntimeException {
    container(
        String.format("%s", value.getClass().getName()),
        () -> {
          classInfo.visit(value, this);
        });
  }

  @Override
  public void visitString(String value) {
    append("string(%s)", value);
  }

  @Override
  public void visitCharacter(Character value) {
    append("character(%s)", value);
  }

  @Override
  public void visitBoolean(Boolean value) {
    append("boolean(%s)", value);
  }

  @Override
  public void visitByte(Byte value) {
    append("byte(%s)", value);
  }

  @Override
  public void visitShort(Short value) {
    append("short(%s)", value);
  }

  @Override
  public void visitInteger(Integer value) {
    append("integer(%s)", value);
  }

  @Override
  public void visitLong(Long value) {
    append("long(%s)", value);
  }

  @Override
  public void visitFloat(Float value) {
    append("float(%s)", value);
  }

  @Override
  public void visitDouble(Double value) {
    append("double(%s)", value);
  }

  @Override
  public void visitTargetConfiguration(TargetConfiguration value) throws RuntimeException {
    if (value instanceof EmptyTargetConfiguration) {
      append("configuration<>");
    } else if (value instanceof HostTargetConfiguration) {
      append("configuration<hostPlatform>");
    } else if (value instanceof DefaultTargetConfiguration) {
      append(
          "configuration<targetPlatform(%s)>",
          ((DefaultTargetConfiguration) value).getTargetPlatform().getFullyQualifiedName());
    } else {
      throw new IllegalArgumentException("Cannot visit target configuration: " + value);
    }
  }

  private void container(String label, Runnable runner) {
    append("%s<", label);
    indent++;
    try (Scope ignored =
        () -> {
          indent--;
          newline();
          append(">");
        }) {
      runner.run();
    }
  }

  private void append(String value) {
    builder.append(value);
  }

  private void append(String format, Object... args) {
    builder.append(String.format(format, args));
  }

  private void newline() {
    append("\n");
    for (int i = 0; i < indent; i++) {
      append("  ");
    }
  }

  public String getValue() {
    return builder.toString().trim();
  }

  @Override
  public void visitPath(Path path) {
    append("path(%s)", PathFormatter.pathWithUnixSeparators(path));
  }
}
