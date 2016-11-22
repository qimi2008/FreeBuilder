/*
 * Copyright 2016 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.inferred.freebuilder.processor.naming;

import org.inferred.freebuilder.processor.Metadata.Property;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

public interface NamingConvention {
  /**
   * Verifies {@code method} is an abstract getter following this naming convention. Any
   * deviations will be logged as an error.
   */
  Property.Builder getPropertyNamesOrNull(TypeElement valueType, ExecutableElement method);
}
