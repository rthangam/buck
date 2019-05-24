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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.DefaultTargetConfiguration;
import com.facebook.buck.core.model.impl.HostTargetConfiguration;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehaviorTag;
import com.facebook.buck.core.rulekey.CustomFieldSerializationTag;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehaviorTag;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.impl.DefaultClassInfoFactory;
import com.facebook.buck.rules.modern.impl.UnconfiguredBuildTargetTypeInfo;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.facebook.buck.rules.modern.impl.ValueTypeInfos.ExcludedValueTypeInfo;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Implementation of Serialization of Buildables.
 *
 * <p>This works by walking all referenced values with a ValueVisitor.
 *
 * <p>Referenced "dynamic" objects (i.e. implementations of AddsToRuleKey) are serialized as simply
 * a hash of their serialized representation. The serialization of a complex object is then
 * effectively a merkle tree. This allows us to share the serialized representation of shared
 * objects (for c++ particularly, there are many shared references to the PreprocessorDelegate and
 * other such fields).
 */
public class Serializer {

  public static final int TARGET_CONFIGURATION_TYPE_EMPTY = 0;
  public static final int TARGET_CONFIGURATION_TYPE_HOST = 1;
  public static final int TARGET_CONFIGURATION_TYPE_DEFAULT = 2;

  private static final int MAX_INLINE_LENGTH = 100;
  private final ConcurrentHashMap<AddsToRuleKey, Either<HashCode, byte[]>> cache =
      new ConcurrentHashMap<>();
  private final SourcePathRuleFinder ruleFinder;
  private final ImmutableMap<Path, Optional<String>> cellMap;
  private final Delegate delegate;
  private final Path rootCellPath;

  /**
   * The first time a "dynamic" object is encountered (including Buildables themselves),
   * registerNewValue will be called. This should return a unique HashCode representation (this
   * hashCode will become the serialized representation of that object and deserialization will
   * depend on a Deserializer.DataProvider to do the reverse lookup back to data/children).
   */
  public interface Delegate {
    HashCode registerNewValue(
        AddsToRuleKey instance, byte[] data, ImmutableList<HashCode> children);
  }

  public Serializer(
      SourcePathRuleFinder ruleFinder, CellPathResolver cellResolver, Delegate delegate) {
    this.ruleFinder = ruleFinder;
    this.delegate = delegate;
    this.rootCellPath = cellResolver.getCellPathOrThrow(Optional.empty());
    this.cellMap =
        cellResolver.getKnownRoots().stream()
            .collect(ImmutableMap.toImmutableMap(root -> root, cellResolver::getCanonicalCellName));
  }

  /**
   * Serializes an object. For small objects, the full serialized format will be returned as a
   * byte[]. For larger objects, the representation will be recorded with the delegate and the hash
   * will be returned.
   */
  public <T extends AddsToRuleKey> HashCode serialize(T instance) throws IOException {
    Either<HashCode, byte[]> serialize =
        serialize(instance, DefaultClassInfoFactory.forInstance(instance));
    return serialize.transform(
        left -> left, right -> delegate.registerNewValue(instance, right, ImmutableList.of()));
  }

  /** See Serialize(T instance) above. */
  public <T extends AddsToRuleKey> Either<HashCode, byte[]> serialize(
      T instance, ClassInfo<T> classInfo) throws IOException {
    if (cache.containsKey(instance)) {
      return Objects.requireNonNull(cache.get(instance));
    }

    Visitor visitor = reserialize(instance, classInfo);

    return Objects.requireNonNull(
        cache.computeIfAbsent(
            instance,
            ignored -> {
              byte[] data = visitor.byteStream.toByteArray();
              ImmutableList<HashCode> children =
                  visitor.children.build().distinct().collect(ImmutableList.toImmutableList());
              return data.length < MAX_INLINE_LENGTH && children.isEmpty()
                  ? Either.ofRight(data)
                  : Either.ofLeft(delegate.registerNewValue(instance, data, children));
            }));
  }

  /**
   * Returns the serialized bytes of the instance. This is useful if the caller has lost the value
   * recorded in a previous serialize call.
   */
  public <T extends AddsToRuleKey> byte[] reserialize(T instance) throws IOException {
    ClassInfo<T> classInfo = DefaultClassInfoFactory.forInstance(instance);
    // TODO(cjhopman): Return children too?
    return reserialize(instance, classInfo).byteStream.toByteArray();
  }

  private <T extends AddsToRuleKey> Visitor reserialize(T instance, ClassInfo<T> classInfo)
      throws IOException {
    Visitor visitor = new Visitor(instance.getClass());

    Optional<CustomClassBehaviorTag> serializerTag =
        CustomBehaviorUtils.getBehavior(instance.getClass(), CustomClassSerialization.class);
    if (serializerTag.isPresent()) {
      @SuppressWarnings("unchecked")
      CustomClassSerialization<T> customSerializer =
          (CustomClassSerialization<T>) serializerTag.get();
      customSerializer.serialize(instance, visitor);
    } else {
      classInfo.visit(instance, visitor);
    }
    return visitor;
  }

  private class Visitor implements ValueVisitor<IOException> {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    DataOutputStream stream = new DataOutputStream(byteStream);
    Stream.Builder<HashCode> children = Stream.builder();

    public Visitor(Class<? extends AddsToRuleKey> clazz) throws IOException {
      writeString(clazz.getName());
    }

    @Override
    public <T> void visitList(ImmutableList<T> value, ValueTypeInfo<T> innerType)
        throws IOException {
      stream.writeInt(value.size());
      for (T e : value) {
        innerType.visit(e, this);
      }
    }

    @Override
    public <T> void visitSet(ImmutableSet<T> value, ValueTypeInfo<T> innerType) throws IOException {
      stream.writeInt(value.size());
      for (T e : value) {
        innerType.visit(e, this);
      }
    }

    @Override
    public <T> void visitSortedSet(ImmutableSortedSet<T> value, ValueTypeInfo<T> innerType)
        throws IOException {
      visitSet(value, innerType);
    }

    @Override
    public <T> void visitOptional(Optional<T> value, ValueTypeInfo<T> innerType)
        throws IOException {
      stream.writeBoolean(value.isPresent());
      if (value.isPresent()) {
        innerType.visit(value.get(), this);
      }
    }

    @Override
    public void visitOutputPath(OutputPath value) throws IOException {
      stream.writeBoolean(value instanceof PublicOutputPath);
      writeString(value.getPath().toString());
    }

    private void writeString(String value) throws IOException {
      // TODO(cjhopman): This doesn't correctly handle large strings.
      Preconditions.checkState(value.length() < 10000);
      stream.writeUTF(value);
    }

    @Override
    public void visitSourcePath(SourcePath value) throws IOException {
      if (value instanceof DefaultBuildTargetSourcePath) {
        value = ruleFinder.getRule(value).get().getSourcePathToOutput();
        Objects.requireNonNull(value);
      }

      if (value instanceof ExplicitBuildTargetSourcePath) {
        stream.writeBoolean(true);
        ExplicitBuildTargetSourcePath buildTargetSourcePath = (ExplicitBuildTargetSourcePath) value;
        writeValue(buildTargetSourcePath.getTarget(), new TypeToken<BuildTarget>() {});
        writeString(buildTargetSourcePath.getResolvedPath().toString());
      } else if (value instanceof ForwardingBuildTargetSourcePath) {
        visitSourcePath(((ForwardingBuildTargetSourcePath) value).getDelegate());
      } else if (value instanceof PathSourcePath) {
        PathSourcePath pathSourcePath = (PathSourcePath) value;
        stream.writeBoolean(false);
        writeValue(
            getCellName(pathSourcePath.getFilesystem()), new TypeToken<Optional<String>>() {});
        writeString(pathSourcePath.getRelativePath().toString());
      } else {
        throw new IllegalStateException(
            String.format("Cannot serialize SourcePath of type %s.", value.getClass().getName()));
      }
    }

    private <T> void writeValue(T value, TypeToken<T> type) throws IOException {
      ValueTypeInfoFactory.forTypeToken(type).visit(value, this);
    }

    @Override
    public <T> void visitField(
        Field field,
        T value,
        ValueTypeInfo<T> valueTypeInfo,
        List<Class<? extends CustomFieldBehaviorTag>> behavior)
        throws IOException {
      try {
        Optional<CustomFieldSerializationTag> serializerTag =
            CustomBehaviorUtils.get(CustomFieldSerializationTag.class, behavior);

        if (serializerTag.isPresent()) {
          if (serializerTag.get() instanceof DefaultFieldSerialization) {
            @SuppressWarnings("unchecked")
            ValueTypeInfo<T> typeInfo =
                (ValueTypeInfo<T>)
                    ValueTypeInfoFactory.forTypeToken(TypeToken.of(field.getGenericType()));

            typeInfo.visit(value, this);
            return;
          }

          Verify.verify(
              serializerTag.get() instanceof CustomFieldSerialization,
              "Unrecognized serialization behavior %s.",
              serializerTag.get().getClass().getName());

          @SuppressWarnings("unchecked")
          CustomFieldSerialization<T> customSerializer =
              (CustomFieldSerialization<T>) serializerTag.get();
          customSerializer.serialize(value, this);
          return;
        }

        Verify.verify(
            !(valueTypeInfo instanceof ExcludedValueTypeInfo),
            "Cannot serialize excluded fields. Either add @AddToRuleKey or specify custom field/class serialization.");

        valueTypeInfo.visit(value, this);
      } catch (RuntimeException e) {
        throw new BuckUncheckedExecutionException(
            e, "When visiting %s.%s.", field.getDeclaringClass().getName(), field.getName());
      }
    }

    @Override
    public <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo)
        throws IOException {
      Either<HashCode, byte[]> serialized = serialize(value, classInfo);
      if (serialized.isLeft()) {
        stream.writeBoolean(true);
        writeBytes(serialized.getLeft().asBytes());
        children.add(serialized.getLeft());
      } else {
        stream.writeBoolean(false);
        writeBytes(serialized.getRight());
      }
    }

    @Override
    public void visitPath(Path path) throws IOException {
      if (path.isAbsolute()) {
        stream.writeBoolean(true);
        Path cellPath = rootCellPath;
        Optional<String> cellName = Optional.empty();
        for (Map.Entry<Path, Optional<String>> candidate : cellMap.entrySet()) {
          if (path.startsWith(candidate.getKey())) {
            cellPath = candidate.getKey();
            cellName = candidate.getValue();
          }
        }
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<String>>() {})
            .visit(cellName, this);
        writeString(cellPath.relativize(path).toString());
      } else {
        stream.writeBoolean(false);
        stream.writeUTF(path.toString());
      }
    }

    @Override
    public void visitString(String value) throws IOException {
      writeString(value);
    }

    @Override
    public void visitCharacter(Character value) throws IOException {
      stream.writeChar(value);
    }

    @Override
    public void visitBoolean(Boolean value) throws IOException {
      stream.writeBoolean(value);
    }

    @Override
    public void visitByte(Byte value) throws IOException {
      stream.writeByte(value);
    }

    @Override
    public void visitShort(Short value) throws IOException {
      stream.writeShort(value);
    }

    @Override
    public void visitInteger(Integer value) throws IOException {
      stream.writeInt(value);
    }

    @Override
    public void visitLong(Long value) throws IOException {
      stream.writeLong(value);
    }

    @Override
    public void visitFloat(Float value) throws IOException {
      stream.writeFloat(value);
    }

    @Override
    public void visitDouble(Double value) throws IOException {
      stream.writeDouble(value);
    }

    private void writeBytes(byte[] bytes) throws IOException {
      this.stream.writeInt(bytes.length);
      this.stream.write(bytes);
    }

    @Override
    public <K, V> void visitMap(
        ImmutableMap<K, V> value, ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType)
        throws IOException {
      this.stream.writeInt(value.size());
      RichStream.from(value.entrySet())
          .forEachThrowing(
              entry -> {
                keyType.visit(entry.getKey(), this);
                valueType.visit(entry.getValue(), this);
              });
    }

    @Override
    public <K, V> void visitSortedMap(
        ImmutableSortedMap<K, V> value, ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType)
        throws IOException {
      Preconditions.checkState(value.comparator().equals(Ordering.natural()));
      visitMap(value, keyType, valueType);
    }

    @Override
    public <T> void visitNullable(@Nullable T value, ValueTypeInfo<T> inner) throws IOException {
      this.stream.writeBoolean(value != null);
      if (value != null) {
        inner.visit(value, this);
      }
    }

    @Override
    public void visitTargetConfiguration(TargetConfiguration value) throws IOException {
      if (value instanceof EmptyTargetConfiguration) {
        stream.writeInt(TARGET_CONFIGURATION_TYPE_EMPTY);
      } else if (value instanceof HostTargetConfiguration) {
        stream.writeInt(TARGET_CONFIGURATION_TYPE_HOST);
      } else if (value instanceof DefaultTargetConfiguration) {
        stream.writeInt(TARGET_CONFIGURATION_TYPE_DEFAULT);
        UnconfiguredBuildTargetTypeInfo.INSTANCE.visit(
            ((DefaultTargetConfiguration) value).getTargetPlatform(), this);
      } else {
        throw new IllegalArgumentException("Cannot serialize target configuration: " + value);
      }
    }
  }

  private Optional<String> getCellName(ProjectFilesystem filesystem) {
    return Objects.requireNonNull(cellMap.get(filesystem.getRootPath()));
  }
}
