/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.attribute;

import java.nio.file.attribute.BasicFileAttributes;

/**
 * Interface to be implemented by any {@link AttributeProvider} that provides the ability to do a
 * bulk read of the attributes it provides as an object of a specific class extending
 * {@link BasicFileAttributes}.
 *
 * @author Colin Decker
 */
public interface AttributeReader<A extends BasicFileAttributes> extends AttributeProvider {

  /**
   * Returns the type of the attributes interface this reader supports.
   */
  Class<A> attributesType();

  /**
   * Returns an immutable attributes object containing the current value of one or more attributes
   * from the given metadata.
   */
  A read(FileMetadata metadata);
}
