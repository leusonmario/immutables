/*
    Copyright 2014 Ievgen Lukash

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.value.processor.meta;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.immutables.generator.AnnotationMirrors;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Style;

@Value.Nested
public final class Proto {
  private Proto() {}

  @Value.Immutable(intern = true, builder = false)
  public static abstract class MetaAnnotated {
    @Value.Parameter
    public abstract String name();

    @Value.Parameter
    @Value.Auxiliary
    public abstract Element element();

    @Value.Derived
    @Value.Auxiliary
    @Deprecated
    // TBD Make nullable. Annotation processing bug
    public Optional<Style> style() {
      return Optional.fromNullable(element().getAnnotation(Value.Style.class));
    }

    public static MetaAnnotated from(AnnotationMirror mirror) {
      TypeElement element = (TypeElement) mirror.getAnnotationType().asElement();
      return ImmutableProto.MetaAnnotated.of(
          element.getQualifiedName().toString(),
          element);
    }
  }

  abstract static class Diagnosable {
    /** Element suitable for reporting as a source of declaration which might causing problems. */
    @Value.Auxiliary
    abstract Element element();

    @Value.Auxiliary
    abstract ProcessingEnvironment processing();

    protected Reporter report() {
      return Reporter.from(processing()).withElement(element());
    }
  }

  /**
   * Introspection supertype for the {@link DeclaringType} and {@link DeclaringPackage}
   */
  public static abstract class AbstractDeclaring extends Diagnosable {
    public abstract String name();

    @Value.Lazy
    public List<TypeElement> includedTypes() {
      ImmutableList<TypeMirror> typeMirrors =
          AnnotationMirrors.getTypesFromMirrors(
              Value.Immutable.Include.class.getCanonicalName(),
              "value",
              element().getAnnotationMirrors());

      ImmutableSet<String> typeNames = FluentIterable.from(typeMirrors)
          .filter(DeclaredType.class)
          .transform(DeclatedTypeToElement.FUNCTION)
          .filter(IsPublic.PREDICATE)
          .transform(ElementToName.FUNCTION)
          .toSet();

      if (typeNames.size() != typeMirrors.size()) {
        report().forAnnotation(Value.Immutable.Include.class)
            .warning("Some types were ignored, non-supported for inclusion: duplicates, non reference types, non-public");
      }

      ImmutableList.Builder<TypeElement> builder = ImmutableList.builder();
      for (String typeName : typeNames) {
        builder.add(processing().getElementUtils().getTypeElement(typeName));
      }
      return builder.build();
    }

    @Value.Lazy
    public boolean useImmutableDefaults() {
      @Nullable AnnotationMirror annotation =
          AnnotationMirrors.findAnnotation(
              element().getAnnotationMirrors(),
              Value.Immutable.class);

      if (annotation != null) {
        return annotation.getElementValues().isEmpty();
      }

      return true;
    }

    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
      return AnnotationMirrors.isAnnotationPresent(
          element().getAnnotationMirrors(),
          annotationType);
    }

    @Value.Derived
    @Value.Auxiliary
    public boolean hasInclude() {
      return AnnotationMirrors.isAnnotationPresent(
          element().getAnnotationMirrors(),
          Value.Immutable.Include.class);
    }

    @Value.Derived
    @Value.Auxiliary
    public boolean isEnclosing() {
      return AnnotationMirrors.isAnnotationPresent(
          element().getAnnotationMirrors(),
          Value.Nested.class);
    }

    /**
     * TODO Move to {@link DeclaringType}?.
     * @return true, if is top level
     */
    @Value.Derived
    @Value.Auxiliary
    public boolean isTopLevel() {
      return element().getEnclosingElement() == null
          || element().getEnclosingElement().getKind() == ElementKind.PACKAGE;
    }

    @Value.Derived
    @Value.Auxiliary
    @Deprecated
    // TBD Make Nullable (annotation processor bug)
    public Optional<Value.Immutable> features() {
      return Optional.fromNullable(element().getAnnotation(Value.Immutable.class));
    }

    public boolean isImmutable() {
      return features().isPresent();
    }

    @Value.Derived
    @Value.Auxiliary
    @Deprecated
    // TBD Make Nullable (annotation processor bug)
    public Optional<Value.Style> style() {
      Value.Style style = element().getAnnotation(Value.Style.class);

      if (style != null) {
        return Optional.of(style);
      }

      for (AnnotationMirror mirror : element().getAnnotationMirrors()) {
        MetaAnnotated metaAnnotated = MetaAnnotated.from(mirror);
        @Nullable Value.Style metaStyle = metaAnnotated.style().orNull();
        if (metaStyle != null) {
          return Optional.of(metaStyle);
        }
      }

      return Optional.absent();
    }

    /**
     * Logic honors {@link ElementType} declared on annotation types to avoid some
     * useless checks. But otherwise it's not exhaustive.
     * TODO Move to {@link DeclaringType}?
     */
    @Value.Check
    protected void validate() {
      if (hasInclude() && !isTopLevel()) {
        report().forAnnotation(Value.Immutable.Include.class).error("@Include could not be used on nested types.");
      }
      if (isEnclosing() && !isTopLevel()) {
        report().forAnnotation(Value.Nested.class).error("@Nested should only be used on a top-level types.");
      }
      if (element().getKind() == ElementKind.ENUM) {
        report().error("@Value.* annotations are not supported on enums");
      }
    }
  }

  @Value.Immutable(intern = true)
  public static abstract class DeclaringPackage extends AbstractDeclaring {

    @Override
    @Value.Auxiliary
    public abstract PackageElement element();

    /**
     * Name is the only equivalence attribute. Basically packages are interned by name.
     * @return package name
     */
    @Override
    @Value.Derived
    public String name() {
      return element().isUnnamed() ? "" : element().getQualifiedName().toString();
    }
  }

  @Value.Immutable(intern = true)
  public static abstract class DeclaringType extends AbstractDeclaring {
    @Override
    @Value.Auxiliary
    public abstract TypeElement element();

    @Override
    @Value.Derived
    public String name() {
      return element().getQualifiedName().toString();
    }

    @Override
    @Value.Derived
    @Value.Auxiliary
    public boolean isTopLevel() {
      return element().getEnclosingElement().getKind() == ElementKind.PACKAGE;
    }

    @Value.Derived
    @Value.Auxiliary
    public Optional<DeclaringType> enclosingOf() {
      TypeElement top = element();
      for (Element e = element(); e.getKind() != ElementKind.PACKAGE; e = e.getEnclosingElement()) {
        top = (TypeElement) e;
      }
      if (top != element()) {
        ImmutableProto.DeclaringType enclosingType = ImmutableProto.DeclaringType.builder()
            .processing(processing())
            .element(top)
            .build();

        if (enclosingType.isEnclosing()) {
          return Optional.<DeclaringType>of(enclosingType);
        }
      }

      return Optional.absent();
    }

    @Value.Derived
    @Value.Auxiliary
    public DeclaringPackage packageOf() {
      return ImmutableProto.DeclaringPackage.builder()
          .processing(processing())
          .element(processing().getElementUtils().getPackageOf(element()))
          .build();
    }
  }

  /**
   * Prototypical model for generated derived classes. {@code Protoclass} could be used to projects
   * different kind of derived classes,
   */
  @Value.Immutable
  public static abstract class Protoclass extends Diagnosable {

    /**
     * Source elements stores type element which is used as a source of value type model.
     * It is the annotated class for {@code @Value.Immutable} or type referenced in
     * {@code @Value.Immutable.Include}.
     * @return source element
     */
    public abstract TypeElement sourceElement();

    /**
     * Declaring package that defines value type (usually by import).
     * Or the package in which {@link #declaringType()} resides.
     * @return declaring package
     */
    public abstract DeclaringPackage packageOf();

    /**
     * The class, which is annotated to be a {@code @Value.Immutable},
     * {@code @Value.Immutable.Include} or {@code @Value.Nested}.
     * @return declaring type
     */
    public abstract Optional<DeclaringType> declaringType();

    /**
     * Kind of protoclass declaration, it specifies how exactly the protoclass was declared.
     * @return definition kind
     */
    public abstract Kind kind();

    /**
     * Ad-hoc holder for the current round's {@link ValueTypeComposer}.
     * @return composer
     */
    public abstract ValueTypeComposer composer();

    @Value.Derived
    public TypeVisibility visibility() {
      return TypeVisibility.of(sourceElement());
    }

    public TypeVisibility declaringVisibility() {
      if (declaringType().isPresent()) {
        return TypeVisibility.of(declaringType().get().element());
      }
      return TypeVisibility.PUBLIC;
    }

    /**
     * Element used mostly for error reporting,
     * real model provided by {@link #sourceElement()}.
     */
    @Value.Derived
    @Override
    public Element element() {
      if (declaringType().isPresent()) {
        return declaringType().get().element();
      }
      return packageOf().element();
    }

    @Value.Lazy
    public Immutable features() {
      if (declaringType().isPresent() && !declaringType().get().useImmutableDefaults()) {
        return declaringType().get().features().orNull();
      }
      return styles().defaults();
    }

    @Value.Lazy
    public Styles styles() {
      if (declaringType().isPresent()) {
        Optional<DeclaringType> enclosing = enclosingOf();
        if (enclosing.isPresent()) {
          @Nullable Style enclosingStyle = enclosing.get().style().orNull();
          if (enclosingStyle != null) {
            return Styles.using(enclosingStyle);
          }
        } else {
          @Nullable Style style = declaringType().get().style().orNull();
          if (style != null) {
            return Styles.using(style);
          }
        }
      }
      @Nullable Style packageStyle = packageOf().style().orNull();
      if (packageStyle != null) {
        return Styles.using(packageStyle);
      }
      return Styles.using(Styles.defaultStyle());
    }

    @Value.Derived
    public Optional<DeclaringType> enclosingOf() {
      if (declaringType().isPresent() && kind().isNested()) {
        if (kind().isIncluded()) {
          return declaringType();
        }
        return declaringType().get().enclosingOf();
      }
      return Optional.absent();
    }

    @Value.Lazy
    public ValueType type() {
      return composer().compose(this);
    }

    public enum Kind {
      INCLUDED_IN_PACKAGE,
      INCLUDED_ON_TYPE,
      INCLUDED_IN_TYPE,
      DEFINED_TYPE,
      DEFINED_AND_ENCLOSING_TYPE,
      DEFINED_ENCLOSING_TYPE,
      DEFINED_NESTED_TYPE;

      public boolean isNested() {
        switch (this) {
        case INCLUDED_IN_TYPE:
        case DEFINED_NESTED_TYPE:
          return true;
        default:
          return false;
        }
      }

      public boolean isIncluded() {
        switch (this) {
        case INCLUDED_IN_PACKAGE:
        case INCLUDED_IN_TYPE:
        case INCLUDED_ON_TYPE:
          return true;
        default:
          return false;
        }
      }

      public boolean isEnclosing() {
        switch (this) {
        case DEFINED_AND_ENCLOSING_TYPE:
        case DEFINED_ENCLOSING_TYPE:
          return true;
        default:
          return false;
        }
      }

      public boolean isValue() {
        return this != DEFINED_ENCLOSING_TYPE;
      }
    }
  }

  private enum ElementToName implements Function<TypeElement, String> {
    FUNCTION;
    @Override
    public String apply(TypeElement input) {
      return input.getQualifiedName().toString();
    }
  }

  private enum DeclatedTypeToElement implements Function<DeclaredType, TypeElement> {
    FUNCTION;
    @Override
    public TypeElement apply(DeclaredType input) {
      return (TypeElement) input.asElement();
    }
  }

  private enum IsPublic implements Predicate<Element> {
    PREDICATE;
    @Override
    public boolean apply(Element input) {
      return input.getModifiers().contains(Modifier.PUBLIC);
    }
  }
}
