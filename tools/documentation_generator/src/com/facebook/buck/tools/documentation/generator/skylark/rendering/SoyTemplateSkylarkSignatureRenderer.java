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

package com.facebook.buck.tools.documentation.generator.skylark.rendering;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.common.io.Resources;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.stringtemplate.v4.ST;

/** Renders a soy template suitable for usage with the rest of buckbuild website documents. */
public class SoyTemplateSkylarkSignatureRenderer {

  private static final char DELIMITER_START_CHAR = '%';
  private static final char DELIMITER_STOP_CHAR = '%';
  private static final String FUNCTION_TEMPLATE_NAME = "signature_template.stg";
  private static final String TABLE_OF_CONTENTS_TEMPLATE_NAME = "table_of_contents_template.stg";
  private static final Escaper DOC_ESCAPER =
      Escapers.builder().addEscape('{', "{lb}").addEscape('}', "{rb}").build();
  private static final Escaper VALUE_ESCAPER = Escapers.builder().addEscape('\'', "\\'").build();

  private final LoadingCache<String, String> templateCache;

  public SoyTemplateSkylarkSignatureRenderer() {
    this.templateCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<String, String>() {
                  @Override
                  public String load(String templateName) throws Exception {
                    return loadTemplate(templateName);
                  }
                });
  }

  /**
   * Renders provided Skylark signature into a soy template content similar to manually written
   * templates for all Python DSL functions.
   */
  public String render(SkylarkCallable skylarkSignature) {
    ST stringTemplate = createTemplate(FUNCTION_TEMPLATE_NAME);
    // open and close brace characters are not allowed inside of StringTemplate loops and using
    // named parameters seems nicer than their unicode identifiers
    stringTemplate.add("openCurly", "{");
    stringTemplate.add("closeCurly", "}");
    stringTemplate.add("signature", toMap(skylarkSignature));
    return stringTemplate.render();
  }

  /** Renders a table of contents for the Skylark functions subsection on buck.build website. */
  public String renderTableOfContents(Iterable<SkylarkCallable> signatures) {
    ST stringTemplate = createTemplate(TABLE_OF_CONTENTS_TEMPLATE_NAME);
    stringTemplate.add("openCurly", "{");
    stringTemplate.add("closeCurly", "}");
    stringTemplate.add(
        "signatures",
        Streams.stream(signatures)
            .sorted(Comparator.comparing(SkylarkCallable::name))
            .map(SoyTemplateSkylarkSignatureRenderer::toMap)
            .collect(Collectors.toList()));
    return stringTemplate.render();
  }

  private ST createTemplate(String templateName) {
    return new ST(
        templateCache.getUnchecked(templateName), DELIMITER_START_CHAR, DELIMITER_STOP_CHAR);
  }

  private static String loadTemplate(String templateName) throws IOException {
    URL template = Resources.getResource(SoyTemplateSkylarkSignatureRenderer.class, templateName);
    return Resources.toString(template, StandardCharsets.UTF_8);
  }

  private static ImmutableMap<String, Object> toMap(SkylarkCallable skylarkSignature) {
    ImmutableList.Builder<Param> parameters =
        ImmutableList.<Param>builder().addAll(Arrays.asList(skylarkSignature.parameters()));
    if (!skylarkSignature.extraKeywords().name().isEmpty()) {
      parameters.add(skylarkSignature.extraKeywords());
    }

    return ImmutableMap.of(
        "name",
        skylarkSignature.name(),
        "doc",
        skylarkSignature.doc(),
        "parameters",
        parameters.build().stream()
            .map(SoyTemplateSkylarkSignatureRenderer::toMap)
            .collect(Collectors.toList()));
  }

  private static ImmutableMap<String, String> toMap(Param param) {
    return ImmutableMap.of(
        "name", param.name(),
        "doc", DOC_ESCAPER.escape(param.doc()),
        "defaultValue",
            VALUE_ESCAPER.escape(param.defaultValue().isEmpty() ? "None" : param.defaultValue()));
  }
}
