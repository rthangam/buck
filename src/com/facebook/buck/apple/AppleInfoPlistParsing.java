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

package com.facebook.buck.apple;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.core.exceptions.HumanReadableException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Optional;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/** Utility class to parse Info.plist from an Apple bundle. */
public class AppleInfoPlistParsing {

  // Utility class, do not instantiate.
  private AppleInfoPlistParsing() {}

  /** Extracts the bundle ID (CFBundleIdentifier) from an Info.plist, returning it if present. */
  public static Optional<String> getBundleIdFromPlistStream(Path plistPath, InputStream inputStream)
      throws IOException {
    NSObject bundleID =
        getPropertyValueFromPlistStream(plistPath, inputStream, "CFBundleIdentifier");
    if (bundleID == null) {
      return Optional.empty();
    } else {
      return Optional.of(bundleID.toString());
    }
  }

  /** Extracts the bundle type (CFBundlePackageType) from an Info.plist, returning it if present. */
  public static Optional<String> getBundleTypeFromPlistStream(
      Path plistPath, InputStream inputStream) throws IOException {
    NSObject bundleType =
        getPropertyValueFromPlistStream(plistPath, inputStream, "CFBundlePackageType");
    if (bundleType == null) {
      return Optional.empty();
    } else {
      return Optional.of(bundleType.toString());
    }
  }

  /** Extracts the watchOS app flag (WKWatchKitApp) from an Info.plist, returning it if present. */
  public static Optional<Boolean> isWatchOSAppFromPlistStream(
      Path plistPath, InputStream inputStream) throws IOException {
    NSNumber isWatchOSApp =
        (NSNumber) getPropertyValueFromPlistStream(plistPath, inputStream, "WKWatchKitApp");
    if (isWatchOSApp == null) {
      return Optional.of(Boolean.FALSE);
    } else {
      return Optional.of(isWatchOSApp.boolValue());
    }
  }

  /** Extracts a property value from an Info.plist, returning it if present. */
  public static NSObject getPropertyValueFromPlistStream(
      Path plistPath, InputStream inputStream, String propertyKey) throws IOException {
    NSDictionary infoPlist;
    try (BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream)) {
      try {
        infoPlist = (NSDictionary) PropertyListParser.parse(bufferedInputStream);
      } catch (PropertyListFormatException
          | ParseException
          | ParserConfigurationException
          | SAXException
          | UnsupportedOperationException e) {
        throw new IOException(e);
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new HumanReadableException(
            plistPath + ": the content of the plist is invalid or empty.");
      }
    }
    return infoPlist.objectForKey(propertyKey);
  }
}
