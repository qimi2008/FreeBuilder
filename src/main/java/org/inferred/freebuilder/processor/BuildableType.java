/*
 * Copyright 2014 Google Inc. All rights reserved.
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
package org.inferred.freebuilder.processor;

import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.tryFind;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.util.ElementFilter.typesIn;
import static org.inferred.freebuilder.processor.util.ModelUtils.asElement;
import static org.inferred.freebuilder.processor.util.ModelUtils.findAnnotationMirror;
import static org.inferred.freebuilder.processor.util.ModelUtils.maybeDeclared;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;

import org.inferred.freebuilder.FreeBuilder;
import org.inferred.freebuilder.processor.util.ParameterizedType;

import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

@FreeBuilder
public abstract class BuildableType {

  /** How to merge the values from one Builder into another. */
  public enum MergeBuilderMethod {
    MERGE_DIRECTLY, BUILD_PARTIAL_AND_MERGE
  }

  /** How to convert a partial value into a Builder. */
  public enum PartialToBuilderMethod {
    MERGE_DIRECTLY, TO_BUILDER_AND_MERGE
  }

  public abstract DeclaredType type();
  public abstract ParameterizedType builderType();
  public abstract MergeBuilderMethod mergeBuilder();
  public abstract PartialToBuilderMethod partialToBuilder();
  public abstract BuilderFactory builderFactory();

  public static class Builder extends BuildableType_Builder {}

  public static Optional<BuildableType> create(
      TypeMirror candidateType, Elements elements, Types types) {
    DeclaredType type = maybeDeclared(candidateType).orNull();
    if (type == null) {
      return Optional.absent();
    }
    TypeElement element = asElement(type);

    // Find the builder
    TypeElement builder = tryFind(typesIn(element.getEnclosedElements()), IS_BUILDER_TYPE).orNull();
    if (builder == null) {
      return Optional.absent();
    }

    // Verify the builder can be constructed
    BuilderFactory builderFactory = BuilderFactory.from(builder).orNull();
    if (builderFactory == null) {
      return Optional.absent();
    }

    MergeBuilderMethod mergeFromBuilderMethod;
    if (findAnnotationMirror(element, FreeBuilder.class).isPresent()) {
      /*
       * If the element is annotated @FreeBuilder, assume the necessary methods will be added. We
       * can't check directly as the builder superclass may not have been generated yet. To be
       * strictly correct, we should delay a round if an error type leaves us unsure about this kind
       * of API-changing decision, and then we would work with _any_ Builder-generating API. We
       * would need to drop out part of our own builder superclass, to prevent chains of dependent
       * buildable types leading to quadratic compilation times (not to mention cycles), and leave a
       * dangling super-superclass to pick up next round. As an optimization, though, we would
       * probably skip this for @FreeBuilder-types anyway, to avoid extra types whenever possible,
       * which leaves a lot of complicated code supporting a currently non-existent edge case.
       */
      mergeFromBuilderMethod = MergeBuilderMethod.MERGE_DIRECTLY;
    } else {
      List<ExecutableElement> methods = FluentIterable
          .from(elements.getAllMembers(builder))
          .filter(ExecutableElement.class)
          .filter(new IsCallableMethod())
          .toList();

      // Check there is a build() method
      if (!any(methods, new IsBuildMethod("build", type, types))) {
        return Optional.absent();
      }

      // Check there is a buildPartial() method
      if (!any(methods, new IsBuildMethod("buildPartial", type, types))) {
        return Optional.absent();
      }

      // Check there is a clear() method
      if (!any(methods, new IsClearMethod())) {
        return Optional.absent();
      }

      // Check there is a mergeFrom(Value) method
      if (!any(methods, new IsMergeFromMethod(type, types))) {
        return Optional.absent();
      }

      // Check whether there is a mergeFrom(Builder) method
      if (any(methods, new IsMergeFromMethod(builder.asType(), types))) {
        mergeFromBuilderMethod = MergeBuilderMethod.MERGE_DIRECTLY;
      } else {
        mergeFromBuilderMethod = MergeBuilderMethod.BUILD_PARTIAL_AND_MERGE;
      }
    }

    List<ExecutableElement> valueMethods = FluentIterable
        .from(elements.getAllMembers(element))
        .filter(ExecutableElement.class)
        .filter(new IsCallableMethod())
        .toList();

    // Check whether there is a toBuilder() method
    PartialToBuilderMethod partialToBuilderMethod;
    if (any(valueMethods, new IsToBuilderMethod(builder.asType(), types))) {
      partialToBuilderMethod = PartialToBuilderMethod.TO_BUILDER_AND_MERGE;
    } else {
      partialToBuilderMethod = PartialToBuilderMethod.MERGE_DIRECTLY;
    }

    ParameterizedType builderType;
    try {
      builderType = ParameterizedType
          .from(builder)
          .withParametersFrom(ParameterizedType.from(type));
    } catch (IllegalArgumentException e) {
      // Parameter lists are the wrong length
      return Optional.absent();
    }

    return Optional.of(new Builder()
        .type(type)
        .builderType(builderType)
        .mergeBuilder(mergeFromBuilderMethod)
        .partialToBuilder(partialToBuilderMethod)
        .builderFactory(builderFactory)
        .build());
  }

  private static final class IsCallableMethod implements Predicate<ExecutableElement> {
    @Override
    public boolean apply(ExecutableElement element) {
      boolean isMethod = (element.getKind() == ElementKind.METHOD);
      boolean isPublic = element.getModifiers().contains(Modifier.PUBLIC);
      boolean isNotStatic = !element.getModifiers().contains(Modifier.STATIC);
      boolean declaresNoExceptions = element.getThrownTypes().isEmpty();
      return isMethod && isPublic && isNotStatic && declaresNoExceptions;
    }
  }

  private static final class IsBuildMethod implements Predicate<ExecutableElement> {
    final String methodName;
    final TypeMirror builtType;
    final Types types;

    IsBuildMethod(String methodName, TypeMirror builtType, Types types) {
      this.methodName = methodName;
      this.builtType = builtType;
      this.types = types;
    }

    @Override public boolean apply(ExecutableElement element) {
      if (!element.getParameters().isEmpty()) {
        return false;
      }
      if (!element.getSimpleName().contentEquals(methodName)) {
        return false;
      }
      if (!types.isSubtype(element.getReturnType(), builtType)) {
        return false;
      }
      return true;
    }
  }

  private static final class IsClearMethod implements Predicate<ExecutableElement> {
    @Override public boolean apply(ExecutableElement element) {
      if (!element.getParameters().isEmpty()) {
        return false;
      }
      if (!element.getSimpleName().contentEquals("clear")) {
        return false;
      }
      return true;
    }
  }

  private static final class IsMergeFromMethod implements Predicate<ExecutableElement> {
    final TypeMirror builderType;
    final Types types;

    IsMergeFromMethod(TypeMirror sourceType, Types types) {
      this.builderType = sourceType;
      this.types = types;
    }

    @Override public boolean apply(ExecutableElement element) {
      if (element.getParameters().size() != 1) {
        return false;
      }
      if (!element.getSimpleName().contentEquals("mergeFrom")) {
        return false;
      }
      if (!types.isSubtype(builderType, element.getParameters().get(0).asType())) {
        return false;
      }
      return true;
    }
  }

  private static final class IsToBuilderMethod implements Predicate<ExecutableElement> {
    final TypeMirror builderType;
    final Types types;

    IsToBuilderMethod(TypeMirror sourceType, Types types) {
      this.builderType = sourceType;
      this.types = types;
    }

    @Override public boolean apply(ExecutableElement element) {
      if (element.getParameters().size() != 0) {
        return false;
      }
      if (!element.getSimpleName().contentEquals("toBuilder")) {
        return false;
      }
      if (!types.isSubtype(element.getReturnType(), builderType)) {
        return false;
      }
      return true;
    }
  }

  private static final Predicate<Element> IS_BUILDER_TYPE = new Predicate<Element>() {
    @Override public boolean apply(Element element) {
      return element.getSimpleName().contentEquals("Builder")
          && element.getModifiers().contains(PUBLIC);
    }
  };

}
