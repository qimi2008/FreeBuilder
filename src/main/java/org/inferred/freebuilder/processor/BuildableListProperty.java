package org.inferred.freebuilder.processor;

import static org.inferred.freebuilder.processor.BuildableType.PartialToBuilderMethod.TO_BUILDER_AND_MERGE;
import static org.inferred.freebuilder.processor.BuilderFactory.TypeInference.EXPLICIT_TYPES;
import static org.inferred.freebuilder.processor.BuilderMethods.addAllBuildersOfMethod;
import static org.inferred.freebuilder.processor.BuilderMethods.addAllMethod;
import static org.inferred.freebuilder.processor.BuilderMethods.addMethod;
import static org.inferred.freebuilder.processor.BuilderMethods.clearMethod;
import static org.inferred.freebuilder.processor.BuilderMethods.getBuildersMethod;
import static org.inferred.freebuilder.processor.BuilderMethods.mutator;
import static org.inferred.freebuilder.processor.Util.erasesToAnyOf;
import static org.inferred.freebuilder.processor.Util.upperBound;
import static org.inferred.freebuilder.processor.util.Block.methodBody;
import static org.inferred.freebuilder.processor.util.ModelUtils.maybeDeclared;
import static org.inferred.freebuilder.processor.util.ModelUtils.needsSafeVarargs;
import static org.inferred.freebuilder.processor.util.ModelUtils.overrides;
import static org.inferred.freebuilder.processor.util.feature.FunctionPackage.FUNCTION_PACKAGE;
import static org.inferred.freebuilder.processor.util.feature.SourceLevel.SOURCE_LEVEL;
import static org.inferred.freebuilder.processor.util.feature.SourceLevel.diamondOperator;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.inferred.freebuilder.processor.Metadata.Property;
import org.inferred.freebuilder.processor.util.Block;
import org.inferred.freebuilder.processor.util.Excerpt;
import org.inferred.freebuilder.processor.util.Excerpts;
import org.inferred.freebuilder.processor.util.ParameterizedType;
import org.inferred.freebuilder.processor.util.QualifiedName;
import org.inferred.freebuilder.processor.util.SourceBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

class BuildableListProperty extends PropertyCodeGenerator {

  static class Factory implements PropertyCodeGenerator.Factory {

    @Override
    public Optional<BuildableListProperty> create(Config config) {
      DeclaredType type = maybeDeclared(config.getProperty().getType()).orNull();
      if (type == null || !erasesToAnyOf(type, Collection.class, List.class, ImmutableList.class)) {
        return Optional.absent();
      }

      TypeMirror rawElementType = upperBound(config.getElements(), type.getTypeArguments().get(0));
      BuildableType element = BuildableType
          .create(rawElementType, config.getElements(), config.getTypes()).orNull();
      if (element == null) {
        return Optional.absent();
      }
      boolean needsSafeVarargs = needsSafeVarargs(rawElementType);
      boolean overridesValueInstanceVarargsAddMethod =
          hasValueInstanceVarargsAddMethodOverride(config, rawElementType);
      boolean overridesBuilderVarargsAddMethod =
          hasBuilderVarargsAddMethodOverride(config, element.builderType());
      return Optional.of(new BuildableListProperty(
          config.getMetadata(),
          config.getProperty(),
          needsSafeVarargs,
          overridesValueInstanceVarargsAddMethod,
          overridesBuilderVarargsAddMethod,
          element));
    }

    private static boolean hasValueInstanceVarargsAddMethodOverride(
        Config config, TypeMirror elementType) {
      return overrides(
          config.getBuilder(),
          config.getTypes(),
          addMethod(config.getProperty()),
          config.getTypes().getArrayType(elementType));
    }

    private static boolean hasBuilderVarargsAddMethodOverride(
        Config config, ParameterizedType builderType) {
      TypeMirror rawBuilderType = config.getElements()
          .getTypeElement(builderType.getQualifiedName().toString())
          .asType();
      return overrides(
          config.getBuilder(),
          config.getTypes(),
          addMethod(config.getProperty()),
          config.getTypes().getArrayType(rawBuilderType));
    }
  }

  private final boolean needsSafeVarargs;
  private final boolean overridesValueInstanceVarargsAddMethod;
  private final boolean overridesBuilderVarargsAddMethod;
  private final BuildableType element;

  private BuildableListProperty(
      Metadata metadata,
      Property property,
      boolean needsSafeVarargs,
      boolean overridesValueInstanceVarargsAddMethod,
      boolean overridesBuilderVarargsAddMethod,
      BuildableType element) {
    super(metadata, property);
    this.needsSafeVarargs = needsSafeVarargs;
    this.overridesValueInstanceVarargsAddMethod = overridesValueInstanceVarargsAddMethod;
    this.overridesBuilderVarargsAddMethod = overridesBuilderVarargsAddMethod;
    this.element = element;
  }

  @Override
  public void addBuilderFieldDeclaration(SourceBuilder code) {
    code.addLine("private final %1$s<%2$s> %3$s = new %1$s%4$s();",
        ArrayList.class,
        element.builderType(),
        property.getField(),
        diamondOperator(element.builderType()));
  }

  @Override
  public void addBuilderFieldAccessors(SourceBuilder code) {
    addValueInstanceAdd(code);
    addBuilderAdd(code);
    addValueInstanceVarargsAdd(code);
    addBuilderVarargsAdd(code);
    addAddAllMethods(code);
    addMutate(code);
    addClear(code);
    addGetter(code);
  }

  private void addValueInstanceAdd(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s(%s element) {",
            metadata.getBuilder(), addMethod(property), element.type());
    Block body = methodBody(code, "element");
    if (element.partialToBuilder() == TO_BUILDER_AND_MERGE) {
      body.addLine("  %s.add(element.toBuilder());", property.getField());
    } else {
      body.addLine("  %s.add(%s.mergeFrom(element));",
          property.getField(),
          element.builderFactory().newBuilder(element.builderType(), EXPLICIT_TYPES));
    }
    body.addLine("  return (%s) this;", metadata.getBuilder());
    code.add(body)
        .addLine("}");
  }

  private void addBuilderAdd(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s(%s builder) {",
            metadata.getBuilder(), addMethod(property), element.builderType())
        .add(methodBody(code, "builder")
          .addLine("  %s.add(%s.mergeFrom(builder));",
              property.getField(),
              element.builderFactory().newBuilder(element.builderType(), EXPLICIT_TYPES))
          .addLine("  return (%s) this;", metadata.getBuilder()))
        .addLine("}");
  }

  private void addValueInstanceVarargsAdd(SourceBuilder code) {
    code.addLine("");
    addSafeVarargsForPublicMethod(code, overridesValueInstanceVarargsAddMethod);
    code.add("%s %s(%s... elements) {%n",
            metadata.getBuilder(), addMethod(property), element.type())
        .addLine("  return %s(%s.asList(elements));", addAllMethod(property), Arrays.class)
        .addLine("}");
  }

  private void addBuilderVarargsAdd(SourceBuilder code) {
    code.addLine("");
    addSafeVarargsForPublicMethod(code, overridesBuilderVarargsAddMethod);
    code.add("%s %s(%s... elements) {%n",
            metadata.getBuilder(), addMethod(property), element.builderType())
        .addLine("  return %s(%s.asList(elements));",
            addAllBuildersOfMethod(property), Arrays.class)
        .addLine("}");
  }

  private void addSafeVarargsForPublicMethod(SourceBuilder code, boolean isOverridden) {
    QualifiedName safeVarargs = code.feature(SOURCE_LEVEL).safeVarargs().orNull();
    if (safeVarargs != null && needsSafeVarargs) {
      if (!isOverridden) {
        code.addLine("@%s", safeVarargs)
            .addLine("@%s({\"varargs\"})", SuppressWarnings.class);
      } else {
        code.addLine("@%s({\"unchecked\", \"varargs\"})", SuppressWarnings.class);
      }
    }
    code.add("public ");
    if (safeVarargs != null && needsSafeVarargs && !isOverridden) {
      code.add("final ");
    }
  }

  private void addAddAllMethods(SourceBuilder code) {
    if (code.feature(SOURCE_LEVEL).stream().isPresent()) {
      addSpliteratorValueInstanceAddAll(code);
      addSpliteratorBuilderAddAll(code);
      addIterableValueInstanceAddAll(code);
      addIterableBuilderAddAll(code);
      addStreamValueInstanceAddAll(code);
      addStreamBuilderAddAll(code);
    } else {
      addPreStreamsValueInstanceAddAll(code);
      addPreStreamsBuilderAddAll(code);
    }
  }

  private void addPreStreamsValueInstanceAddAll(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s> elements) {",
            metadata.getBuilder(), addAllMethod(property), Iterable.class, element.type());
    Block body = methodBody(code, "elements");
    body.addLine("  if (elements instanceof %s) {", Collection.class);
    Excerpt size = body.pickUnusedVariableName("elementsSize");
    body.addLine("    int %s = ((%s<?>) elements).size();", size, Collection.class)
        .addLine("    %1$s.ensureCapacity(%1$s.size() + %2$s);", property.getField(), size)
        .addLine("  }")
        .add(Excerpts.forEach(element.type(), "elements", addMethod(property)))
        .addLine("  return (%s) this;", metadata.getBuilder());
    code.add(body)
        .addLine("}");
  }

  private void addPreStreamsBuilderAddAll(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s> elementBuilders) {",
            metadata.getBuilder(),
            addAllBuildersOfMethod(property),
            Iterable.class,
            element.builderType());
    Block body = methodBody(code, "elementBuilders");
    body.addLine("  if (elementBuilders instanceof %s) {", Collection.class);
    Excerpt size = body.pickUnusedVariableName("elementsSize");
    body.addLine("    int %s = ((%s<?>) elementBuilders).size();", size, Collection.class)
        .addLine("    %1$s.ensureCapacity(%1$s.size() + %2$s);", property.getField(), size)
        .addLine("  }")
        .add(Excerpts.forEach(element.builderType(), "elementBuilders", addMethod(property)))
        .addLine("  return (%s) this;", metadata.getBuilder());
    code.add(body)
        .addLine("}");
  }

  private void addSpliteratorValueInstanceAddAll(SourceBuilder code) {
    QualifiedName spliterator = code.feature(SOURCE_LEVEL).spliterator().get();
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s> elements) {",
            metadata.getBuilder(), addAllMethod(property), spliterator, element.type());
    Block body = methodBody(code, "elements");
    body.addLine("  if ((elements.characteristics() & %s.SIZED) != 0) {", spliterator);
    Excerpt newSize = body.pickUnusedVariableName("newSize");
    body.addLine("    long %s = elements.estimateSize() + %s.size();", newSize, property.getField())
        .addLine("    if (%s <= Integer.MAX_VALUE) {", newSize)
        .addLine("      %s.ensureCapacity((int) %s);", property.getField(), newSize)
        .addLine("    }")
        .addLine("  }")
        .addLine("  elements.forEachRemaining(this::%s);", addMethod(property))
        .addLine("  return (%s) this;", metadata.getBuilder());
    code.add(body)
        .addLine("}");
  }

  private void addSpliteratorBuilderAddAll(SourceBuilder code) {
    QualifiedName spliterator = code.feature(SOURCE_LEVEL).spliterator().get();
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s> elementBuilders) {",
            metadata.getBuilder(),
            addAllBuildersOfMethod(property),
            spliterator,
            element.builderType());
    Block body = methodBody(code, "elementBuilders");
    body.addLine("  if ((elementBuilders.characteristics() & %s.SIZED) != 0) {", spliterator);
    Excerpt newSize = body.pickUnusedVariableName("newSize");
    body.addLine("    long %s = elementBuilders.estimateSize() + %s.size();",
            newSize, property.getField())
        .addLine("    if (%s <= Integer.MAX_VALUE) {", newSize)
        .addLine("      %s.ensureCapacity((int) %s);", property.getField(), newSize)
        .addLine("    }")
        .addLine("  }")
        .addLine("  elementBuilders.forEachRemaining(this::%s);", addMethod(property))
        .addLine("  return (%s) this;", metadata.getBuilder());
    code.add(body)
        .addLine("}");
  }

  private void addIterableValueInstanceAddAll(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s> elements) {",
            metadata.getBuilder(), addAllMethod(property), Iterable.class, element.type())
        .addLine("  return %s(elements.spliterator());", addAllMethod(property))
        .addLine("}");
  }

  private void addIterableBuilderAddAll(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s> elementBuilders) {",
            metadata.getBuilder(),
            addAllBuildersOfMethod(property),
            Iterable.class,
            element.builderType())
        .addLine("  return %s(elementBuilders.spliterator());", addAllBuildersOfMethod(property))
        .addLine("}");
  }

  private void addStreamValueInstanceAddAll(SourceBuilder code) {
    QualifiedName baseStream = code.feature(SOURCE_LEVEL).baseStream().get();
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s, ?> elements) {",
            metadata.getBuilder(), addAllMethod(property), baseStream, element.type())
        .addLine("  return %s(elements.spliterator());", addAllMethod(property))
        .addLine("}");
  }

  private void addStreamBuilderAddAll(SourceBuilder code) {
    QualifiedName baseStream = code.feature(SOURCE_LEVEL).baseStream().get();
    code.addLine("")
        .addLine("public %s %s(%s<? extends %s, ?> elementBuilders) {",
            metadata.getBuilder(),
            addAllBuildersOfMethod(property),
            baseStream,
            element.builderType())
        .addLine("  return %s(elementBuilders.spliterator());", addAllBuildersOfMethod(property))
        .addLine("}");
  }

  private void addMutate(SourceBuilder code) {
    ParameterizedType consumer = code.feature(FUNCTION_PACKAGE).consumer().orNull();
    if (consumer == null) {
      return;
    }
    code.addLine("")
        .addLine("public %s %s(%s<? super %s<%s>> mutator) {",
            metadata.getBuilder(),
            mutator(property),
            consumer.getQualifiedName(),
            List.class,
            element.builderType())
        .add(methodBody(code, "mutator")
            .addLine("  mutator.accept(%s);", property.getField())
            .addLine("  return (%s) this;", metadata.getBuilder()))
        .addLine("}");
  }

  private void addClear(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s %s() {", metadata.getBuilder(), clearMethod(property))
        .addLine("  %s.clear();", property.getField())
        .addLine("  return (%s) this;", metadata.getBuilder())
        .addLine("}");
  }

  private void addGetter(SourceBuilder code) {
    code.addLine("")
        .addLine("public %s<%s> %s() {",
            List.class, element.builderType(), getBuildersMethod(property))
        .addLine("  return %s.unmodifiableList(%s);", Collections.class, property.getField())
        .addLine("}");
  }

  @Override
  public void addFinalFieldAssignment(Block code, Excerpt finalField, String builder) {
    addFieldAssignment(code, finalField, builder, "build");
  }

  @Override
  public void addPartialFieldAssignment(Block code, Excerpt finalField, String builder) {
    addFieldAssignment(code, finalField, builder, "buildPartial");
  }

  private void addFieldAssignment(
      Block code,
      Excerpt finalField,
      String builder,
      String buildMethod) {
    Excerpt fieldBuilder = code.pickUnusedVariableName(property.getName() + "Builder");
    Excerpt fieldElement = code.pickUnusedVariableName("element");
    code.addLine("%s<%s> %s = %s.builder();",
            ImmutableList.Builder.class,
            element.type(),
            fieldBuilder,
            ImmutableList.class)
        .addLine("for (%s %s : %s) {",
            element.builderType(), fieldElement, property.getField().on(builder))
        .addLine("  %s.add(%s.%s());", fieldBuilder, fieldElement, buildMethod)
        .addLine("}")
        .addLine("%s = %s.build();", finalField, fieldBuilder);
  }

  @Override
  public void addMergeFromValue(Block code, String value) {
    code.addLine("%s(%s.%s());", addAllMethod(property), value, property.getGetterName());
  }

  @Override
  public void addMergeFromBuilder(Block code, String builder) {
    Excerpt base = Declarations.upcastToGeneratedBuilder(code, metadata, builder);
    code.addLine("%s(%s);", addAllBuildersOfMethod(property), property.getField().on(base));
  }

  @Override
  public void addSetFromResult(SourceBuilder code, Excerpt builder, Excerpt variable) {
    code.addLine("%s.%s(%s);", builder, addAllMethod(property), variable);
  }

  @Override
  public void addClearField(Block code) {
    code.addLine("%s();", clearMethod(property));
  }
}
