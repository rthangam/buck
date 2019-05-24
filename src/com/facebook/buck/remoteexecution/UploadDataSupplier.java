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
package com.facebook.buck.remoteexecution;

import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.util.function.ThrowingSupplier;
import java.io.IOException;
import java.io.InputStream;

/** Used for wrapping access to data for uploads. */
public interface UploadDataSupplier {
  /**
   * Describe what data is being uploaded. It may be helpful to include the size/hash/contents of
   * the data. If doing so, those values should be recomputed (one major use of including that data
   * would be to diagnose cases where the server claims that the data doesn't match the digest).
   */
  default String describe() {
    return "???";
  }

  InputStream get() throws IOException;

  Digest getDigest();

  /** Create a simple UploadDataSupplier. */
  static UploadDataSupplier of(Digest digest, ThrowingSupplier<InputStream, IOException> stream) {
    return new UploadDataSupplier() {
      @Override
      public InputStream get() throws IOException {
        return stream.get();
      }

      @Override
      public Digest getDigest() {
        return digest;
      }
    };
  }
}
