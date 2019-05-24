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

package com.facebook.buck.apple;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Optional;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

class PlistProcessStep implements Step {

  /** Controls what format the plist is output in. */
  public enum OutputFormat {
    /** Output the XML plist format. */
    XML,

    /** Output the Apple binary plist format. */
    BINARY,
    ;
  }

  private final ProjectFilesystem filesystem;
  private final Path input;
  private final Optional<Path> additionalInputToMerge;
  private final Path output;

  /** Only valid if the input .plist is a NSDictionary; ignored otherwise. */
  private final ImmutableMap<String, NSObject> additionalKeys;

  /** Only valid if the input .plist is a NSDictionary; ignored otherwise. */
  private final ImmutableMap<String, NSObject> overrideKeys;

  private final OutputFormat outputFormat;

  public PlistProcessStep(
      ProjectFilesystem filesystem,
      Path input,
      Optional<Path> additionalInputToMerge,
      Path output,
      ImmutableMap<String, NSObject> additionalKeys,
      ImmutableMap<String, NSObject> overrideKeys,
      OutputFormat outputFormat) {
    this.filesystem = filesystem;
    this.input = input;
    this.additionalInputToMerge = additionalInputToMerge;
    this.output = output;
    this.additionalKeys = additionalKeys;
    this.overrideKeys = overrideKeys;
    this.outputFormat = outputFormat;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (InputStream stream = filesystem.newFileInputStream(input);
        BufferedInputStream bufferedStream = new BufferedInputStream(stream)) {
      NSObject infoPlist;
      try {
        infoPlist = PropertyListParser.parse(bufferedStream);
      } catch (PropertyListFormatException
          | ParseException
          | ParserConfigurationException
          | SAXException
          | UnsupportedOperationException e) {
        throw new IOException(input + ": " + e);
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new HumanReadableException(input + ": the content of the plist is invalid or empty.");
      }

      if (infoPlist instanceof NSDictionary) {
        NSDictionary dictionary = (NSDictionary) infoPlist;

        if (additionalInputToMerge.isPresent()) {
          try (InputStream mergeStream =
                  filesystem.newFileInputStream(additionalInputToMerge.get());
              BufferedInputStream mergeBufferedStream = new BufferedInputStream(mergeStream)) {
            NSObject mergeInfoPlist;
            try {
              mergeInfoPlist = PropertyListParser.parse(mergeBufferedStream);
            } catch (PropertyListFormatException
                | ParseException
                | ParserConfigurationException
                | SAXException e) {
              throw new IOException(additionalInputToMerge + ": " + e);
            }
            HashMap<String, NSObject> infoPlistMap = dictionary.getHashMap();
            ((NSDictionary) mergeInfoPlist)
                .getHashMap()
                .forEach(
                    (mergeInfoPlistKey, mergeInfoPlistValue) ->
                        infoPlistMap.merge(
                            mergeInfoPlistKey,
                            mergeInfoPlistValue,
                            (oldInfoPlistValue, newInfoPlistValue) -> {
                              if (oldInfoPlistValue instanceof NSDictionary
                                  && newInfoPlistValue instanceof NSDictionary) {
                                ((NSDictionary) oldInfoPlistValue)
                                    .putAll(((NSDictionary) newInfoPlistValue).getHashMap());
                                return oldInfoPlistValue;
                              }
                              return newInfoPlistValue;
                            }));
          }
        }

        for (ImmutableMap.Entry<String, NSObject> entry : additionalKeys.entrySet()) {
          if (!dictionary.containsKey(entry.getKey())) {
            dictionary.put(entry.getKey(), entry.getValue());
          }
        }

        dictionary.putAll(overrideKeys);
      }

      switch (this.outputFormat) {
        case XML:
          String serializedInfoPlist = infoPlist.toXMLPropertyList();
          filesystem.writeContentsToPath(serializedInfoPlist, output);
          break;
        case BINARY:
          byte[] binaryInfoPlist = BinaryPropertyListWriter.writeToArray(infoPlist);
          filesystem.writeBytesToPath(binaryInfoPlist, output);
          break;
      }
    }

    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "process-plist";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("process-plist %s %s", input, output);
  }
}
