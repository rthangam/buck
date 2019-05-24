/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android.aapt;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.aapt.MiniAapt.ResourceParseException;
import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import javax.xml.xpath.XPathExpressionException;
import org.hamcrest.core.IsEqual;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

public class MiniAaptTest {

  private static final ImmutableList<String> RESOURCES =
      ImmutableList.<String>builder()
          .add(
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
              "<LinearLayout>",
              "<Button android:id=\"@+id/button1\" ",
              "android:layout_toLeftOf=\"@id/button2\" ",
              "android:text=\"@string/text\" />",
              "<Button android:id=\"@+id/button3\" ",
              "style:attribute=\"@style/Buck.Theme\" ",
              "android:background=\"@drawable/some_image\" />",
              "<TextView tools:showIn=\"@layout/some_layout\" android:id=\"@id/android:empty\" />",
              "</LinearLayout>")
          .build();

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final SourcePathResolver resolver = new TestActionGraphBuilder().getSourcePathResolver();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testFindingResourceIdsInXml()
      throws IOException, XPathExpressionException, ResourceParseException {
    filesystem.writeLinesToPath(RESOURCES, Paths.get("resource.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    ImmutableSet.Builder<RDotTxtEntry> references = ImmutableSet.builder();
    aapt.processXmlFile(filesystem, Paths.get("resource.xml"), references);

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertEquals(
        definitions,
        ImmutableSet.<RDotTxtEntry>of(
            new FakeRDotTxtEntry(IdType.INT, RType.ID, "button1"),
            new FakeRDotTxtEntry(IdType.INT, RType.ID, "button3")));

    assertEquals(
        references.build(),
        ImmutableSet.<RDotTxtEntry>of(
            new FakeRDotTxtEntry(IdType.INT, RType.DRAWABLE, "some_image"),
            new FakeRDotTxtEntry(IdType.INT, RType.STRING, "text"),
            new FakeRDotTxtEntry(IdType.INT, RType.STYLE, "Buck_Theme"),
            new FakeRDotTxtEntry(IdType.INT, RType.ID, "button2")));
  }

  @Test
  public void testParsingFilesUnderValuesDirectory() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<string name=\"hello\">Hello, <xliff:g id=\"name\">%s</xliff:g>!</string>",
                "<plurals name=\"people\">",
                "   <item quantity=\"zero\">ignore1</item>",
                "   <item quantity=\"many\">ignore2</item>",
                "</plurals>",
                "<skip />",
                "<integer name=\"number\">100</integer>",
                "<dimen name=\"dimension\">100sp</dimen>",
                "<declare-styleable name=\"MyNiceView\">",
                "   <attr name=\"titleText\" />",
                "   <attr name=\"subtitleText\" format=\"string\" />",
                "   <attr name=\"complexAttr\">",
                "       <enum name=\"shouldBeIgnored\" value=\"0\" />",
                "       <enum name=\"alsoIgnore\" value=\"1\" />",
                "       <flag name=\"uselessFlag\" value=\"0x00\" />",
                "   </attr>",
                "   <attr name=\"android:layout_gravity\" />",
                "   <item name=\"should_be_ignored\" />",
                "</declare-styleable>",
                "<eat-comment />",
                "<item type=\"id\" name=\"some_id\" />",
                "<style name=\"Widget.Theme\">",
                "  <item name=\"ignoreMe\" />",
                "</style>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertEquals(
        definitions,
        ImmutableSet.<RDotTxtEntry>of(
            new FakeRDotTxtEntry(IdType.INT, RType.STRING, "hello"),
            new FakeRDotTxtEntry(IdType.INT, RType.PLURALS, "people"),
            new FakeRDotTxtEntry(IdType.INT, RType.INTEGER, "number"),
            new FakeRDotTxtEntry(IdType.INT, RType.DIMEN, "dimension"),
            new FakeRDotTxtEntry(IdType.INT_ARRAY, RType.STYLEABLE, "MyNiceView"),
            new FakeRDotTxtEntry(IdType.INT, RType.STYLEABLE, "MyNiceView_titleText"),
            new FakeRDotTxtEntry(IdType.INT, RType.STYLEABLE, "MyNiceView_subtitleText"),
            new FakeRDotTxtEntry(IdType.INT, RType.STYLEABLE, "MyNiceView_complexAttr"),
            new FakeRDotTxtEntry(IdType.INT, RType.STYLEABLE, "MyNiceView_android_layout_gravity"),
            new FakeRDotTxtEntry(IdType.INT, RType.ATTR, "titleText"),
            new FakeRDotTxtEntry(IdType.INT, RType.ATTR, "subtitleText"),
            new FakeRDotTxtEntry(IdType.INT, RType.ATTR, "complexAttr"),
            new FakeRDotTxtEntry(IdType.INT, RType.ID, "some_id"),
            new FakeRDotTxtEntry(IdType.INT, RType.STYLE, "Widget_Theme")));

    boolean foundElement = false;
    for (RDotTxtEntry definition : definitions) {
      if (definition.name.equals("MyNiceView")) {
        assertEquals("{ 0x7f060001,0x7f060002,0x7f060003,0x7f060004 }", definition.idValue);
        foundElement = true;
      }
    }
    assertTrue(foundElement);
  }

  @Test
  public void testParentIsSet() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                    + "<resources>\n"
                    + "    <attr name=\"justAttr\"/>\n"
                    + "    <declare-styleable name=\"MyLayout\">\n"
                    + "        <attr name=\"myAttr\"/>\n"
                    + "        <attr name=\"myAttr2\"/>\n"
                    + "    </declare-styleable>\n"
                    + "    <declare-styleable name=\"MyLayout_Layout\">\n"
                    + "        <attr name=\"android:text\"/>\n"
                    + "        <attr name=\"android:color\"/>\n"
                    + "    </declare-styleable>\n"
                    + "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertThat(definitions.size(), equalTo(9));
    for (RDotTxtEntry entry : definitions) {
      switch (entry.name) {
        case "justAttr":
          assertEquals("justAttr", entry.parent);
          break;
        case "MyLayout":
          assertEquals("MyLayout", entry.parent);
          break;
        case "MyLayout_myAttr":
          assertEquals("MyLayout", entry.parent);
          break;
        case "MyLayout_myAttr2":
          assertEquals("MyLayout", entry.parent);
          break;
        case "MyLayout_Layout":
          assertEquals("MyLayout_Layout", entry.parent);
          break;
        case "MyLayout_Layout_android_text":
          assertEquals("MyLayout_Layout", entry.parent);
          break;
        case "MyLayout_Layout_android_color":
          assertEquals("MyLayout_Layout", entry.parent);
          break;
        case "myAttr":
          assertEquals("myAttr", entry.parent);
          break;
        case "myAttr2":
          assertEquals("myAttr2", entry.parent);
          break;
        default:
          fail("Unexpected entry: " + entry.name);
      }
    }
  }

  @Test
  public void testParsingValuesExcludedFromResMap() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources exclude-from-buck-resource-map=\"true\">",
                "<string name=\"hello\">Hello, <xliff:g id=\"name\">%s</xliff:g>!</string>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertTrue(definitions.isEmpty());
  }

  @Test
  public void testParsingValuesNotExcludedFromResMap() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources exclude-from-buck-resource-map=\"false\">",
                "<string name=\"hello\">Hello, <xliff:g id=\"name\">%s</xliff:g>!</string>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertEquals(
        definitions,
        ImmutableSet.<RDotTxtEntry>of(new FakeRDotTxtEntry(IdType.INT, RType.STRING, "hello")));
  }

  @Test
  public void testParsingAndroidDrawables() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<bitmap xmlns:android=\"http://schemas.android.com/apk/res/android\">",
                "  xmlns:fbui=\"http://schemas.android.com/apk/res-auto\"",
                "  android:src=\"@drawable/other_bitmap\"",
                "  >",
                "</bitmap>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("android_drawable.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processDrawables(filesystem, Paths.get("android_drawable.xml"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertThat(
        definitions,
        IsEqual.equalToObject(
            ImmutableSet.<RDotTxtEntry>of(
                new FakeRDotTxtEntry(IdType.INT, RType.DRAWABLE, "android_drawable"))));
  }

  @Test
  public void testParsingCustomDrawables() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<app-network xmlns:android=\"http://schemas.android.com/apk/res/android\">",
                "  xmlns:fbui=\"http://schemas.android.com/apk/res-auto\"",
                "  fbui:imageUri=\"http://facebook.com\"",
                "  android:width=\"128px\"",
                "  android:height=\"128px\"",
                "  fbui:density=\"160\"",
                "  >",
                "</app-network>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("custom_drawable.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processDrawables(filesystem, Paths.get("custom_drawable.xml"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertThat(
        definitions,
        IsEqual.equalToObject(
            ImmutableSet.<RDotTxtEntry>of(
                new FakeRDotTxtEntry(
                    IdType.INT,
                    RType.DRAWABLE,
                    "custom_drawable",
                    RDotTxtEntry.CustomDrawableType.CUSTOM))));
  }

  @Test
  public void testParsingGrayscaleImage() throws IOException, ResourceParseException {
    ImmutableList<String> lines = ImmutableList.<String>builder().add("").build();
    filesystem.writeLinesToPath(lines, Paths.get("fbui_tomato.png"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of(),
            /* isGrayscaleImageProcessingEnabled */ true,
            MiniAapt.ResourceCollectionType.R_DOT_TXT);
    aapt.processDrawables(filesystem, Paths.get("fbui_tomato.g.png"));

    Set<RDotTxtEntry> definitions =
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources();

    assertThat(
        definitions,
        IsEqual.equalToObject(
            ImmutableSet.<RDotTxtEntry>of(
                new FakeRDotTxtEntry(
                    IdType.INT,
                    RType.DRAWABLE,
                    "fbui_tomato",
                    RDotTxtEntry.CustomDrawableType.GRAYSCALE_IMAGE))));
  }

  @Test(expected = ResourceParseException.class)
  public void testInvalidResourceType() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<resourcetype name=\"number\">100</resourcetype>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));
  }

  @Test(expected = ResourceParseException.class)
  public void testInvalidItemResource() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<item name=\"number\">100</item>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));
  }

  @Test
  public void testInvalidDefinition() throws XPathExpressionException, IOException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<LinearLayout>",
                "<Button android:id=\"@+string/button1\" ",
                "android:layout_toLeftOf=\"@id/button2\" ",
                "android:text=\"@string/text\" />",
                "</LinearLayout>")
            .build();

    Path resource = Paths.get("resource.xml");
    filesystem.writeLinesToPath(lines, resource);

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    try {
      aapt.processXmlFile(filesystem, resource, ImmutableSet.builder());
      fail("MiniAapt should throw parsing '@+string/button1'");
    } catch (ResourceParseException e) {
      assertThat(e.getMessage(), containsString("Invalid definition of a resource"));
    }
  }

  @Test
  public void testInvalidReference() throws IOException, XPathExpressionException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<LinearLayout>",
                "<Button android:id=\"@+id/button1\" ",
                "android:layout_toLeftOf=\"@someresource/button2\" ",
                "android:text=\"@string/text\" />",
                "</LinearLayout>")
            .build();

    Path resource = Paths.get("resource.xml");
    filesystem.writeLinesToPath(lines, resource);

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    try {
      aapt.processXmlFile(filesystem, resource, ImmutableSet.builder());
      fail("MiniAapt should throw parsing '@someresource/button2'");
    } catch (ResourceParseException e) {
      assertThat(e.getMessage(), containsString("Invalid reference '@someresource/button2'"));
    }
  }

  @Test
  public void testMissingNameAttribute() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage("Error: expected a 'name' attribute in node 'string' with value 'Howdy!'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<string notname=\"hello\">Howdy!</string>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("values.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processValuesFile(filesystem, Paths.get("values.xml"));
  }

  @Test
  public void testVerifyReferences()
      throws IOException, XPathExpressionException, ResourceParseException {
    filesystem.writeLinesToPath(RESOURCES, Paths.get("resource.xml"));

    ImmutableList<String> rDotTxt =
        ImmutableList.of(
            "int string text 0x07010001",
            "int style Buck_Theme 0x07020001",
            "int id button2 0x07030001");

    Path depRTxt = Paths.get("dep/R.txt");
    filesystem.writeLinesToPath(rDotTxt, depRTxt);

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of(depRTxt));
    ImmutableSet.Builder<RDotTxtEntry> references = ImmutableSet.builder();
    aapt.processXmlFile(filesystem, Paths.get("resource.xml"), references);

    Set<RDotTxtEntry> missing = aapt.verifyReferences(filesystem, references.build());

    assertEquals(
        ImmutableSet.<RDotTxtEntry>of(
            new FakeRDotTxtEntry(IdType.INT, RType.DRAWABLE, "some_image")),
        missing);
  }

  @Test
  public void testInvalidNodeId()
      throws IOException, XPathExpressionException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage("Invalid definition of a resource: '@button2'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<LinearLayout>",
                "<Button android:id=\"@+id/button1\" ",
                "android:layout_toLeftOf=\"@button2\" />",
                "</LinearLayout>")
            .build();

    Path resource = Paths.get("resource.xml");
    filesystem.writeLinesToPath(lines, resource);

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processXmlFile(filesystem, resource, ImmutableSet.builder());
  }

  @Test
  public void testProcessFileNamesInDirectory() throws IOException, ResourceParseException {
    ProjectFilesystemView filesystemView = filesystem.asView();

    filesystem.touch(Paths.get("res/drawable/icon.png"));
    filesystem.touch(Paths.get("res/drawable/another_icon.png.orig"));
    filesystem.touch(Paths.get("res/drawable-ldpi/nine_patch.9.png"));
    filesystem.touch(Paths.get("res/drawable-ldpi/.DS_Store"));
    filesystem.touch(Paths.get("res/transition-v19/some_transition.xml"));
    filesystem.writeContentsToPath(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<resources>"
            + "<bool name=\"v\">false</bool>"
            + "</resources>",
        Paths.get("res/values/value.xml~"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());
    aapt.processFileNamesInDirectory(filesystemView, Paths.get("res/drawable"));
    aapt.processFileNamesInDirectory(filesystemView, Paths.get("res/drawable-ldpi"));
    aapt.processFileNamesInDirectory(filesystemView, Paths.get("res/transition-v19"));
    aapt.processValues(
        filesystemView,
        new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId("")),
        Paths.get("res/values"));

    assertEquals(
        ImmutableSet.<RDotTxtEntry>of(
            new FakeRDotTxtEntry(IdType.INT, RType.DRAWABLE, "icon"),
            new FakeRDotTxtEntry(IdType.INT, RType.DRAWABLE, "nine_patch"),
            new FakeRDotTxtEntry(IdType.INT, RType.TRANSITION, "some_transition")),
        ((RDotTxtResourceCollector) aapt.getResourceCollector()).getResources());
  }

  @Test
  public void testDotSeparatedResourceNames()
      throws IOException, XPathExpressionException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<LinearLayout>",
                "<Button android:id=\"@+id/button1\" ",
                "android:text=\"@string/com.buckbuild.taskname\" />",
                "</LinearLayout>")
            .build();

    Path resource = Paths.get("resource.xml");
    filesystem.writeLinesToPath(lines, resource);

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    ImmutableSet.Builder<RDotTxtEntry> references = ImmutableSet.builder();
    aapt.processXmlFile(filesystem, Paths.get("resource.xml"), references);

    assertEquals(
        references.build(),
        ImmutableSet.<RDotTxtEntry>of(
            new FakeRDotTxtEntry(IdType.INT, RType.STRING, "com_buckbuild_taskname")));
  }

  @Test
  public void ignoresValidPublicResourceType() throws IOException, ResourceParseException {
    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public name=\"some_resource_name\" type=\"string\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("public.xml"));
  }

  @Test
  public void invalidPublicResourceWithNoName() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage(
        "Error parsing file 'public.xml', expected a 'name' attribute in \n" + "'[public: null]'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public type=\"string\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("public.xml"));
  }

  @Test
  public void invalidPublicResourceWithEmptyName() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage(
        "Error parsing file 'public.xml', expected a 'name' attribute in \n" + "'[public: null]'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public name=\"\" type=\"string\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("public.xml"));
  }

  @Test
  public void invalidPublicResourceWithNoType() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage(
        "Error parsing file 'public.xml', expected a 'type' attribute in: \n" + "'[public: null]'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public name=\"some_resource_name\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("public.xml"));
  }

  @Test
  public void invalidPublicResourceWithEmptyType() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage(
        "Error parsing file 'public.xml', expected a 'type' attribute in: \n" + "'[public: null]'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public name=\"some_resource_name\" type=\"\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("public.xml"));
  }

  @Test
  public void invalidPublicResourceWithUnknownType() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage(
        "Invalid resource type 'unknown_type' in <public> resource 'some_resource_name' in file 'public.xml'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public name=\"some_resource_name\" type=\"unknown_type\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("public.xml"));
  }

  @Test
  public void validPublicResourceTypeInInvalidFile() throws IOException, ResourceParseException {
    thrown.expect(ResourceParseException.class);
    thrown.expectMessage(
        "<public> resource 'some_resource_name' must be declared in res/values/public.xml, but was declared in 'non-public.xml'");

    ImmutableList<String> lines =
        ImmutableList.<String>builder()
            .add(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<resources>",
                "<public name=\"some_resource_name\" type=\"string\"/>",
                "</resources>")
            .build();

    filesystem.writeLinesToPath(lines, Paths.get("non-public.xml"));

    MiniAapt aapt =
        new MiniAapt(
            resolver,
            filesystem,
            FakeSourcePath.of(filesystem, "res"),
            Paths.get("R.txt"),
            ImmutableSet.of());

    aapt.processValuesFile(filesystem, Paths.get("non-public.xml"));
  }
}
