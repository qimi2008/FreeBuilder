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

import static org.inferred.freebuilder.processor.BuilderFactory.TypeInference.INFERRED_TYPES;
import static org.inferred.freebuilder.processor.BuilderMethods.getBuilderMethod;
import static org.inferred.freebuilder.processor.BuilderMethods.mutator;
import static org.inferred.freebuilder.processor.BuilderMethods.setter;
import static org.inferred.freebuilder.processor.util.Block.methodBody;
import static org.inferred.freebuilder.processor.util.feature.FunctionPackage.FUNCTION_PACKAGE;

import com.google.common.base.Optional;

import org.inferred.freebuilder.processor.BuildableType.MergeBuilderMethod;
import org.inferred.freebuilder.processor.BuildableType.PartialToBuilderMethod;
import org.inferred.freebuilder.processor.Metadata.Property;
import org.inferred.freebuilder.processor.util.Block;
import org.inferred.freebuilder.processor.util.Excerpt;
import org.inferred.freebuilder.processor.util.ParameterizedType;
import org.inferred.freebuilder.processor.util.PreconditionExcerpts;
import org.inferred.freebuilder.processor.util.SourceBuilder;

/**
 * {@link PropertyCodeGenerator} for <b>buildable</b> types: that is, types with a Builder class
 * providing a similar API to proto or &#64;FreeBuilder:<ul>
 * <li> a public constructor, or static builder()/newBuilder() method;
 * <li> build(), buildPartial() and clear() methods; and
 * <li> a mergeWith(Value) method.
 * </ul>
 */
class BuildableProperty extends PropertyCodeGenerator {

  static class Factory implements PropertyCodeGenerator.Factory {

    @Override
    public Optional<BuildableProperty> create(Config config) {
      BuildableType type = BuildableType.create(
          config.getProperty().getType(), config.getElements(), config.getTypes()).orNull();
      if (type == null) {
        return Optional.absent();
      }

      return Optional.of(new BuildableProperty(config.getMetadata(), config.getProperty(), type));
    }
  }

  private final BuildableType type;

  private BuildableProperty(Metadata metadata, Property property, BuildableType type) {
    super(metadata, property);
    this.type = type;
  }

  @Override
  public void addBuilderFieldDeclaration(SourceBuilder code) {
    code.addLine("private final %s %s = %s;",
        type.builderType(),
        property.getField(),
        type.builderFactory().newBuilder(type.builderType(), INFERRED_TYPES));
  }

  @Override
  public void addBuilderFieldAccessors(SourceBuilder code) {
    addSetter(code, metadata);
    addSetterTakingBuilder(code, metadata);
    addMutate(code, metadata);
    addGetter(code, metadata);
  }

  private void addSetter(SourceBuilder code, Metadata metadata) {
    code.addLine("")
        .addLine("/**")
        .addLine(" * Sets the value to be returned by %s.",
            metadata.getType().javadocNoArgMethodLink(property.getGetterName()))
        .addLine(" *")
        .addLine(" * @return this {@code %s} object", metadata.getBuilder().getSimpleName())
        .addLine(" * @throws NullPointerException if {@code %s} is null", property.getName())
        .addLine(" */");
    addAccessorAnnotations(code);
    code.addLine("public %s %s(%s %s) {",
            metadata.getBuilder(),
            setter(property),
            property.getType(),
            property.getName())
        .add(methodBody(code, property.getName())
            .add(PreconditionExcerpts.checkNotNull(property.getName()))
            .addLine("  %s.clear();", property.getField())
            .addLine("  %s.mergeFrom(%s);", property.getField(), property.getName())
            .addLine("  return (%s) this;", metadata.getBuilder()))
        .addLine("}");
  }

  private void addSetterTakingBuilder(SourceBuilder code, Metadata metadata) {
    code.addLine("")
        .addLine("/**")
        .addLine(" * Sets the value to be returned by %s.",
            metadata.getType().javadocNoArgMethodLink(property.getGetterName()))
        .addLine(" *")
        .addLine(" * @return this {@code %s} object", metadata.getBuilder().getSimpleName())
        .addLine(" * @throws NullPointerException if {@code builder} is null")
        .addLine(" */")
        .addLine("public %s %s(%s builder) {",
            metadata.getBuilder(),
            setter(property),
            type.builderType())
        .addLine("  return %s(builder.build());", setter(property))
        .addLine("}");
  }

  private void addMutate(SourceBuilder code, Metadata metadata) {
    ParameterizedType consumer = code.feature(FUNCTION_PACKAGE).consumer().orNull();
    if (consumer == null) {
      return;
    }
    code.addLine("")
        .addLine("/**")
        .addLine(" * Applies {@code mutator} to the builder for the value that will be")
        .addLine(" * returned by %s.",
            metadata.getType().javadocNoArgMethodLink(property.getGetterName()))
        .addLine(" *")
        .addLine(" * <p>This method mutates the builder in-place. {@code mutator} is a void")
        .addLine(" * consumer, so any value returned from a lambda will be ignored.")
        .addLine(" *")
        .addLine(" * @return this {@code %s} object", metadata.getBuilder().getSimpleName())
        .addLine(" * @throws NullPointerException if {@code mutator} is null")
        .addLine(" */")
        .addLine("public %s %s(%s<%s> mutator) {",
            metadata.getBuilder(),
            mutator(property),
            consumer.getQualifiedName(),
            type.builderType())
        .add(methodBody(code, "mutator")
            .addLine("  mutator.accept(%s);", property.getField())
            .addLine("  return (%s) this;", metadata.getBuilder()))
        .addLine("}");
  }

  private void addGetter(SourceBuilder code, Metadata metadata) {
    code.addLine("")
        .addLine("/**")
        .addLine(" * Returns a builder for the value that will be returned by %s.",
            metadata.getType().javadocNoArgMethodLink(property.getGetterName()))
        .addLine(" */")
        .addLine("public %s %s() {", type.builderType(), getBuilderMethod(property))
        .addLine("  return %s;", property.getField())
        .addLine("}");
  }

  @Override
  public void addFinalFieldAssignment(SourceBuilder code, Excerpt finalField, String builder) {
    code.addLine("%s = %s.build();", finalField, property.getField().on(builder));
  }

  @Override
  public void addPartialFieldAssignment(SourceBuilder code, Excerpt finalField, String builder) {
    code.addLine("%s = %s.buildPartial();", finalField, property.getField().on(builder));
  }

  @Override
  public void addMergeFromValue(Block code, String value) {
    code.addLine("%s.mergeFrom(%s.%s());", property.getField(), value, property.getGetterName());
  }

  @Override
  public void addMergeFromBuilder(Block code, String builder) {
    code.add("%s.mergeFrom(%s.%s()", property.getField(), builder, getBuilderMethod(property));
    if (type.mergeBuilder() == MergeBuilderMethod.BUILD_PARTIAL_AND_MERGE) {
      code.add(".buildPartial()");
    }
    code.add(");\n");
  }

  @Override
  public void addSetBuilderFromPartial(Block code, String builder) {
    if (type.partialToBuilder() == PartialToBuilderMethod.TO_BUILDER_AND_MERGE) {
      code.add("%s.%s().mergeFrom(%s.toBuilder());",
          builder, getBuilderMethod(property), property.getField());
    } else {
      code.add("%s.%s().mergeFrom(%s);",
          builder, getBuilderMethod(property), property.getField());
    }
  }

  @Override
  public void addSetFromResult(SourceBuilder code, Excerpt builder, Excerpt variable) {
    code.addLine("%s.%s(%s);", builder, setter(property), variable);
  }

  @Override
  public void addClearField(Block code) {
    code.addLine("%s.clear();", property.getField());
  }
}
