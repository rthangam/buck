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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.BaseToolchainProvider;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainWithCapability;
import com.facebook.buck.cxx.RelativeLinkArg;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.EmptyMemoizerDeserialization;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import com.facebook.buck.rules.modern.SerializationTestHelper;
import com.facebook.buck.rules.modern.SourcePathResolverSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.util.Memoizer;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class BuildableSerializerTest extends AbstractValueVisitorTest {
  private SourcePathRuleFinder ruleFinder;
  private CellPathResolver cellResolver;
  private SourcePathResolver resolver;
  private CustomToolchainProvider toolchainProvider;

  @Before
  public void setUp() {
    resolver = createStrictMock(SourcePathResolver.class);
    ruleFinder = createStrictMock(SourcePathRuleFinder.class);
    cellResolver = createMock(CellPathResolver.class);
    toolchainProvider = new CustomToolchainProvider();

    expect(cellResolver.getKnownRoots())
        .andReturn(
            ImmutableSortedSet.of(rootFilesystem.getRootPath(), otherFilesystem.getRootPath()))
        .anyTimes();

    expect(cellResolver.getCanonicalCellName(rootFilesystem.getRootPath()))
        .andReturn(Optional.empty())
        .anyTimes();
    expect(cellResolver.getCanonicalCellName(otherFilesystem.getRootPath()))
        .andReturn(Optional.of("other"))
        .anyTimes();

    expect(cellResolver.getCellPathOrThrow(Optional.empty()))
        .andReturn(rootFilesystem.getRootPath())
        .anyTimes();
  }

  @Override
  @Test
  public void withExcludeFromRuleKey() throws Exception {
    test(new WithExcludeFromRuleKey());
  }

  class CustomToolchainProvider extends BaseToolchainProvider {
    private Map<String, Toolchain> toolchains = new HashMap<>();

    @Override
    public Toolchain getByName(String toolchainName) {
      if (toolchains.containsKey(toolchainName)) {
        return toolchains.get(toolchainName);
      }
      throw new ToolchainInstantiationException("");
    }

    @Override
    public boolean isToolchainPresent(String toolchainName) {
      return toolchains.containsKey(toolchainName);
    }

    @Override
    public boolean isToolchainCreated(String toolchainName) {
      return isToolchainPresent(toolchainName);
    }

    @Override
    public boolean isToolchainFailed(String toolchainName) {
      return !isToolchainPresent(toolchainName);
    }

    @Override
    public <T extends ToolchainWithCapability> Collection<String> getToolchainsWithCapability(
        Class<T> capability) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ToolchainInstantiationException> getToolchainInstantiationException(
        String toolchainName) {
      throw new UnsupportedOperationException();
    }
  }

  <T extends AddsToRuleKey> T test(T instance) throws IOException {
    return test(instance, expected -> expected);
  }

  <T extends AddsToRuleKey> T test(T instance, Function<String, String> expectedMapper)
      throws IOException {
    replay(cellResolver, ruleFinder);
    AddsToRuleKey reconstructed =
        SerializationTestHelper.serializeAndDeserialize(
            instance,
            AddsToRuleKey.class,
            ruleFinder,
            cellResolver,
            resolver,
            toolchainProvider,
            s -> s.isPresent() ? otherFilesystem : rootFilesystem);
    Preconditions.checkState(instance.getClass().equals(reconstructed.getClass()));
    verify(cellResolver, ruleFinder);
    assertEquals(expectedMapper.apply(stringify(instance)), stringify(reconstructed));
    return (T) reconstructed;
  }

  private String stringify(AddsToRuleKey instance) {
    StringifyingValueVisitor visitor = new StringifyingValueVisitor();
    DefaultClassInfoFactory.forInstance(instance).visit(instance, visitor);
    return String.format(
        "%s {\n  %s\n}",
        instance.getClass().getName(), Joiner.on("\n  ").join(visitor.getValue().split("\n")));
  }

  @Override
  @Test
  public void outputPath() throws IOException {
    test(new WithOutputPath());
  }

  @Test
  @Override
  public void sourcePath() throws IOException {
    test(new WithSourcePath());
  }

  @Override
  @Test
  public void set() throws IOException {
    test(new WithSet());
  }

  @Override
  @Test
  public void sortedSet() throws Exception {
    test(new WithSortedSet());
  }

  @Test
  @Override
  public void list() throws IOException {
    test(new WithList());
  }

  @Test
  @Override
  public void optional() throws IOException {
    test(new WithOptional());
  }

  @Test
  @Override
  public void optionalInt() throws Exception {
    test(new WithOptionalInt());
  }

  @Test
  @Override
  public void simple() throws IOException {
    test(new Simple());
  }

  @Test
  @Override
  public void superClass() throws IOException {
    test(new TwiceDerived());
  }

  @Test
  @Override
  public void empty() throws IOException {
    test(new Empty());
  }

  @Test
  @Override
  public void addsToRuleKey() throws IOException {
    test(new WithAddsToRuleKey());
  }

  @Test
  @Override
  public void complex() throws IOException {
    BuildRule mockRule = createStrictMock(BuildRule.class);
    BuildTarget target =
        BuildTargetFactory.newInstance(rootFilesystem.getRootPath(), "//some/build:target");
    expect(ruleFinder.getRule((SourcePath) anyObject())).andReturn(Optional.of(mockRule));
    mockRule.getSourcePathToOutput();
    expectLastCall().andReturn(ExplicitBuildTargetSourcePath.of(target, Paths.get("and.path")));
    replay(mockRule);
    test(
        new Complex(),
        expected ->
            expected.replace(
                "SourcePath(//some/build:target)",
                "SourcePath(Pair(//some/build:target, and.path))"));
    verify(mockRule);
  }

  @Test
  @Override
  public void buildTarget() throws IOException {
    test(new WithBuildTarget());
  }

  @Test
  @Override
  public void buildTargetWithEmptyConfiguration() throws IOException {
    test(new WithBuildTargetWithEmptyConfiguration());
  }

  @Test
  @Override
  public void buildTargetWithHostConfiguration() throws IOException {
    test(new WithBuildTargetWithHostConfiguration());
  }

  @Override
  @Test
  public void pattern() throws Exception {
    test(new WithPattern());
  }

  @Override
  @Test
  public void anEnum() throws Exception {
    test(new WithEnum());
  }

  @Override
  @Test
  public void nonHashableSourcePathContainer() throws Exception {
    test(new WithNonHashableSourcePathContainer());
  }

  @Override
  @Test
  public void map() throws Exception {
    test(new WithMap());
  }

  @Override
  @Test
  public void sortedMap() throws Exception {
    test(new WithSortedMap());
  }

  @Override
  @Test
  public void supplier() throws Exception {
    test(new WithSupplier());
  }

  @Override
  @Test
  public void nullable() throws Exception {
    test(new WithNullable());
  }

  @Override
  @Test
  public void either() throws Exception {
    test(new WithEither());
  }

  @Override
  @Test
  public void excluded() throws Exception {
    expectedException.expect(Exception.class);
    expectedException.expectMessage(Matchers.containsString("Cannot serialize excluded fields."));
    test(new WithExcluded());
  }

  @Override
  @Test
  public void immutables() throws Exception {
    test(new WithImmutables());
  }

  @Test
  public void customFieldBehavior() throws Exception {
    WithCustomFieldBehavior initialInstance = new WithCustomFieldBehavior();
    initialInstance.memoizer.get(() -> "bad");
    WithCustomFieldBehavior newInstance = test(initialInstance);
    assertEquals("okay", newInstance.memoizer.get(() -> "okay"));
  }

  @Override
  @Test
  public void stringified() throws Exception {
    expectedException.expect(Exception.class);
    expectedException.expectMessage(Matchers.containsString("Cannot serialize excluded fields."));
    test(new WithStringified());
  }

  @Override
  @Test
  public void wildcards() throws Exception {
    test(new WithWildcards());
  }

  private static class WithCustomFieldBehavior implements FakeBuildable {
    // By default, fields without @AddToRuleKey can't be serialized. DefaultFieldSerialization
    // serializes them as though they were added to the key.
    @CustomFieldBehavior(DefaultFieldSerialization.class)
    private final String excluded = "excluded";

    @AddToRuleKey
    @CustomFieldBehavior(SpecialFieldSerialization.class)
    private final ImmutableList<String> paths = ImmutableList.of("Hello", " ", "world", "!");

    @CustomFieldBehavior(EmptyMemoizerDeserialization.class)
    private final Memoizer memoizer = new Memoizer();
  }

  private static class SpecialFieldSerialization
      implements CustomFieldSerialization<ImmutableList<String>> {
    @Override
    public <E extends Exception> void serialize(
        ImmutableList<String> value, ValueVisitor<E> serializer) throws E {
      serializer.visitString("key");
    }

    @Override
    public <E extends Exception> ImmutableList<String> deserialize(ValueCreator<E> deserializer)
        throws E {
      assertEquals("key", deserializer.createString());
      return ImmutableList.of("Hello", " ", "world", "!");
    }
  }

  @Test
  public void customClassBehavior() throws Exception {
    test(new WithCustomClassBehavior());
  }

  @CustomClassBehavior(SpecialClassSerialization.class)
  private static class WithCustomClassBehavior implements FakeBuildable {
    private final String value = "value";
    @AddToRuleKey private final int number = 3;
  }

  private static class SpecialClassSerialization
      implements CustomClassSerialization<WithCustomClassBehavior> {
    @Override
    public <E extends Exception> void serialize(
        WithCustomClassBehavior instance, ValueVisitor<E> serializer) throws E {
      assertEquals("value", instance.value);
      assertEquals(3, instance.number);
      serializer.visitString("special");
    }

    @Override
    public <E extends Exception> WithCustomClassBehavior deserialize(ValueCreator<E> deserializer)
        throws E {
      assertEquals("special", deserializer.createString());
      return new WithCustomClassBehavior();
    }
  }

  @Test
  public void sourcePathResolver() throws Exception {
    WithSourcePathResolver reconstructed = test(new WithSourcePathResolver());
    assertEquals(resolver, reconstructed.resolver);
  }

  private static class WithSourcePathResolver implements FakeBuildable {
    @CustomFieldBehavior(SourcePathResolverSerialization.class)
    private final SourcePathResolver resolver = null;
  }

  @Test
  public void relativeLinkArg() throws Exception {
    Path relativeDir = rootFilesystem.getPath("some", "relative");
    RelativeLinkArg linkArg =
        new RelativeLinkArg(PathSourcePath.of(rootFilesystem, relativeDir.resolve("libname")));
    RelativeLinkArg deserialized = test(linkArg);

    assertEquals(
        String.format("-L%s -lname", rootFilesystem.resolve(relativeDir)), deserialized.toString());
  }

  static class SomeToolchain implements Toolchain {
    public static final String NAME = "SomeToolchain";
    public static final SomeToolchain INSTANCE = new SomeToolchain();

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public String toString() {
      return "A toolchain";
    }
  }

  static class ObjectWithToolchain implements AddsToRuleKey {
    @AddToRuleKey private final Toolchain toolchain;

    ObjectWithToolchain(Toolchain toolchain) {
      this.toolchain = toolchain;
    }
  }

  @Test
  public void objectWithToolchain() throws IOException {
    toolchainProvider.toolchains.put(SomeToolchain.NAME, SomeToolchain.INSTANCE);
    ObjectWithToolchain object = new ObjectWithToolchain(SomeToolchain.INSTANCE);
    ObjectWithToolchain deserialized = test(object);
    assertEquals(object.toolchain, deserialized.toolchain);
  }

  @Test
  public void remoteExecutionEnabled() throws Exception {
    RemoteExecutionConditional enabled = new RemoteExecutionConditional(true);
    RemoteExecutionConditional newInstance = test(enabled);
    assertTrue(newInstance.enabled);
  }

  @Test
  public void remoteExecutionDisabled() throws Exception {
    RemoteExecutionConditional enabled = new RemoteExecutionConditional(false);
    expectedException.expect(RuntimeException.class);
    test(enabled);
  }

  private static class RemoteExecutionConditional implements FakeBuildable {
    // By default, fields without @AddToRuleKey can't be serialized. DefaultFieldSerialization
    // serializes them as though they were added to the key.
    @CustomFieldBehavior(RemoteExecutionEnabled.class)
    private final boolean enabled;

    private RemoteExecutionConditional(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
