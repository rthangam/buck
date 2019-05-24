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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.modern.Buildable;
import org.junit.Test;

public class StringifyingValueVisitorTest extends AbstractValueVisitorTest {
  @Override
  @Test
  public void outputPath() {
    assertEquals(
        "output:OutputPath(some/path)\n"
            + "publicOutput:OutputPath(public.path)\n"
            + "publicAsOutputPath:OutputPath(other.public.path)",
        stringify(new WithOutputPath()));
  }

  @Override
  @Test
  public void sourcePath() {
    assertEquals("path:SourcePath(/project/root/some/path)", stringify(new WithSourcePath()));
  }

  @Override
  @Test
  public void set() {
    assertEquals(
        "present:Set<\n"
            + "  string(hello)\n"
            + "  string(world)\n"
            + "  string(!)\n"
            + ">\n"
            + "empty:Set<\n"
            + ">",
        stringify(new WithSet()));
  }

  @Override
  @Test
  public void sortedSet() {
    assertEquals(
        "present:SortedSet<\n"
            + "  string(1world)\n"
            + "  string(2!)\n"
            + "  string(3hello)\n"
            + ">\n"
            + "empty:SortedSet<\n"
            + ">",
        stringify(new WithSortedSet()));
  }

  @Override
  @Test
  public void list() {
    assertEquals(
        "present:List<\n"
            + "  string(hello)\n"
            + "  string(world)\n"
            + "  string(!)\n"
            + ">\n"
            + "empty:List<\n"
            + ">",
        stringify(new WithList()));
  }

  @Override
  @Test
  public void optional() {
    assertEquals(
        "present:Optional<\n" + "  string(hello)\n" + ">\n" + "empty:Optional.empty()",
        stringify(new WithOptional()));
  }

  @Test
  @Override
  public void optionalInt() {
    assertEquals(
        "present:boolean(true)integer(7)\nempty:boolean(false)", stringify(new WithOptionalInt()));
  }

  @Override
  @Test
  public void simple() {
    assertEquals(
        "string:string(string)\n"
            + "integer:integer(1)\n"
            + "character:character(c)\n"
            + "value:float(2.5)\n"
            + "doubles:List<\n"
            + "  double(1.1)\n"
            + "  double(2.2)\n"
            + "  double(3.3)\n"
            + ">",
        stringify(new Simple()));
  }

  @Override
  @Test
  public void superClass() {
    assertEquals(
        "string:string(string)\n"
            + "integer:integer(1)\n"
            + "character:character(c)\n"
            + "value:float(2.5)\n"
            + "doubles:List<\n"
            + "  double(1.1)\n"
            + "  double(2.2)\n"
            + "  double(3.3)\n"
            + ">\n"
            + "number:double(2.3)\n"
            + "number:integer(3)",
        stringify(new TwiceDerived()));
  }

  @Override
  @Test
  public void empty() {
    assertEquals("", stringify(new Empty()));
  }

  @Override
  @Test
  public void addsToRuleKey() {
    assertEquals(
        "nested:com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$NestedAppendable<\n"
            + "  appendable:Optional<\n"
            + "    com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "      sp:SourcePath(/project/root/appendable.path)\n"
            + "    >\n"
            + "  >\n"
            + ">\n"
            + "function:null\n"
            + "list:List<\n"
            + "  com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "    sp:SourcePath(/project/root/appendable.path)\n"
            + "  >\n"
            + "  com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "    sp:SourcePath(/project/root/appendable.path)\n"
            + "  >\n"
            + ">",
        stringify(new WithAddsToRuleKey()));
  }

  @Override
  @Test
  public void complex() {
    assertEquals(
        "value:Optional<\n"
            + "  List<\n"
            + "    SortedSet<\n"
            + "    >\n"
            + "    SortedSet<\n"
            + "      SourcePath(//some/build:target)\n"
            + "      SourcePath(/project/root/some/path)\n"
            + "    >\n"
            + "  >\n"
            + ">\n"
            + "string:string(hello)\n"
            + "number:integer(0)\n"
            + "outputs:List<\n"
            + "  OutputPath(hello.txt)\n"
            + "  OutputPath(world.txt)\n"
            + ">\n"
            + "otherOutput:OutputPath(other.file)\n"
            + "appendable:com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "  sp:SourcePath(/project/root/appendable.path)\n"
            + ">",
        stringify(new Complex()));
  }

  @Test
  @Override
  public void buildTarget() {
    assertEquals(
        "target:path(/project/other)Optional<\n"
            + "  string(other)\n"
            + ">string(//some)string(target)SortedSet<\n"
            + "  string(flavor1)\n"
            + "  string(flavor2)\n"
            + ">configuration<targetPlatform(//platform:platform)>",
        stringify(new WithBuildTarget()));
  }

  @Test
  @Override
  public void buildTargetWithEmptyConfiguration() {
    assertEquals(
        "target:path(/project/other)Optional<\n"
            + "  string(other)\n"
            + ">string(//some)string(target)SortedSet<\n"
            + "  string(flavor1)\n"
            + "  string(flavor2)\n"
            + ">configuration<>",
        stringify(new WithBuildTargetWithEmptyConfiguration()));
  }

  @Test
  @Override
  public void buildTargetWithHostConfiguration() {
    assertEquals(
        "target:path(/project/other)Optional<\n"
            + "  string(other)\n"
            + ">string(//some)string(target)SortedSet<\n"
            + "  string(flavor1)\n"
            + "  string(flavor2)\n"
            + ">configuration<hostPlatform>",
        stringify(new WithBuildTargetWithHostConfiguration()));
  }

  private String stringify(Buildable value) {
    StringifyingValueVisitor visitor = new StringifyingValueVisitor();
    DefaultClassInfoFactory.forInstance(value).visit(value, visitor);
    return visitor.getValue();
  }

  @Override
  @Test
  public void pattern() {
    assertEquals("pattern:string(abcd)", stringify(new WithPattern()));
  }

  @Override
  @Test
  public void anEnum() {
    assertEquals(
        "type:string(GOOD)\n" + "otherType:Optional<\n" + "  string(BAD)\n" + ">",
        stringify(new WithEnum()));
  }

  @Override
  @Test
  public void nonHashableSourcePathContainer() {
    assertEquals(
        "container:SourcePath(/project/root/some/path)",
        stringify(new WithNonHashableSourcePathContainer()));
  }

  @Override
  @Test
  public void map() {
    assertEquals(
        "emptyMap:Map<\n"
            + ">\n"
            + "pathMap:Map<\n"
            + "  key<\n"
            + "    string(path)\n"
            + "  >\n"
            + "  value<\n"
            + "    SourcePath(/project/root/some/path)\n"
            + "  >\n"
            + "  key<\n"
            + "    string(target)\n"
            + "  >\n"
            + "  value<\n"
            + "    SourcePath(Pair(other//some:target#flavor1,flavor2, other.path))\n"
            + "  >\n"
            + ">",
        stringify(new WithMap()));
  }

  @Override
  @Test
  public void sortedMap() {
    assertEquals(
        "emptyMap:SortedMap<\n"
            + ">\n"
            + "pathMap:SortedMap<\n"
            + "  key<\n"
            + "    string(path)\n"
            + "  >\n"
            + "  value<\n"
            + "    SourcePath(/project/root/some/path)\n"
            + "  >\n"
            + "  key<\n"
            + "    string(target)\n"
            + "  >\n"
            + "  value<\n"
            + "    SourcePath(Pair(other//some:target#flavor1,flavor2, other.path))\n"
            + "  >\n"
            + ">",
        stringify(new WithSortedMap()));
  }

  @Override
  @Test
  public void supplier() {
    assertEquals(
        "stringSupplier:string(string)\n" + "weakPath:SourcePath(/project/root/some.path)",
        stringify(new WithSupplier()));
  }

  @Override
  @Test
  public void nullable() {
    assertEquals(
        "nullString:null\n" + "nullPath:null\n" + "nonNullPath:SourcePath(/project/root/some.path)",
        stringify(new WithNullable()));
  }

  @Override
  @Test
  public void either() {
    assertEquals(
        "leftString:boolean(true)string(left)\n"
            + "rightPath:boolean(false)SourcePath(/project/root/some.path)",
        stringify(new WithEither()));
  }

  @Override
  @Test
  public void excluded() {
    assertEquals("excluded:\n" + "nullNotAnnoted:", stringify(new WithExcluded()));
  }

  @Override
  @Test
  public void immutables() {
    assertEquals(
        "tupleInterfaceData:com.facebook.buck.rules.modern.impl.TupleInterfaceData<\n"
            + "  first:SourcePath(/project/root/first.path)\n"
            + "  second:string(world)\n"
            + ">\n"
            + "immutableInterfaceData:com.facebook.buck.rules.modern.impl.ImmutableInterfaceData<\n"
            + "  first:SourcePath(/project/root/second.path)\n"
            + "  second:string(world)\n"
            + ">\n"
            + "tupleClassData:com.facebook.buck.rules.modern.impl.TupleClassData<\n"
            + "  first:SourcePath(/project/root/third.path)\n"
            + "  second:string(world)\n"
            + ">\n"
            + "immutableClassData:com.facebook.buck.rules.modern.impl.ImmutableClassData<\n"
            + "  first:SourcePath(/project/root/fourth.path)\n"
            + "  second:string(world)\n"
            + ">",
        stringify(new WithImmutables()));
  }

  @Override
  @Test
  public void stringified() {
    assertEquals("stringified:", stringify(new WithStringified()));
  }

  @Override
  @Test
  public void wildcards() {
    assertEquals(
        "path:Optional.empty()\n"
            + "appendables:List<\n"
            + "  com.facebook.buck.rules.modern.impl.AbstractValueVisitorTest$Appendable<\n"
            + "    sp:SourcePath(/project/root/appendable.path)\n"
            + "  >\n"
            + ">",
        stringify(new WithWildcards()));
  }

  @Override
  @Test
  public void withExcludeFromRuleKey() {
    assertEquals("sourcePath:\n" + "otherPath:", stringify(new WithExcludeFromRuleKey()));
  }
}
