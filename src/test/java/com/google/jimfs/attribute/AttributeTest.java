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

import static org.truth0.Truth.ASSERT;

import org.junit.Test;

/**
 * @author Colin Decker
 */
public class AttributeTest {

  @Test
  public void testAttributeSpecs() {
    Attribute foo = Attribute.unsettable("test", "foo", String.class);
    Attribute bar = Attribute.settable("test", "bar", Long.class);
    Attribute baz = Attribute.settableOnCreate("test", "baz", Integer.class);

    checkAttributeSpec(foo, "foo", String.class, false, false);
    checkAttributeSpec(bar, "bar", Long.class, true, false);
    checkAttributeSpec(baz, "baz", Integer.class, true, true);
  }

  private static void checkAttributeSpec(Attribute attribute,
      String name, Class<?> type, boolean settable, boolean settableOnCreate) {
    ASSERT.that(attribute.name()).isEqualTo(name);
    ASSERT.that(attribute.key()).isEqualTo("test:" + name);
    ASSERT.that(attribute.type()).isEqualTo(type);
    ASSERT.that(attribute.isUserSettable()).isEqualTo(settable);
    ASSERT.that(attribute.isSettableOnCreate()).isEqualTo(settableOnCreate);
  }
}