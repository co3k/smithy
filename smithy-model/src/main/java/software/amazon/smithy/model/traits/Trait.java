/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.traits;

import java.util.stream.Stream;
import software.amazon.smithy.model.FromSourceLocation;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.node.ToNode;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.Validator;
import software.amazon.smithy.utils.OptionalUtils;
import software.amazon.smithy.utils.Pair;

/**
 * Traits provide additional context and semantics to shapes.
 *
 * <p>A trait complements a {@link Shape} by providing additional
 * information to help correctly interpret any specific representation
 * of it or to add information about constraints on the logical structure
 * of the {@code Shape}. For example, one {@code Trait} object might
 * reflect details about how a {@code Shape} is bound to JSON while
 * another might reflect details about how that same {@code Shape} is
 * bound to Ion.
 *
 * <p>Traits are discovered through Java SPI using the {@link TraitService}
 * interface. All traits that are defined in a Smithy MUST provide a
 * TraitService in order for the concrete trait type to be created for
 * the trait in code. Otherwise, the trait is created as a
 * {@link DynamicTrait}.</p>
 *
 * <p>Traits may perform as much validation in their constructor; any
 * exception thrown while creating a trait when assembling a model will
 * automatically include the name of the trait in the thrown exception
 * message. Any validation that requires more context than is provided to
 * the trait constructor should be performed by implementing a
 * {@link Validator} class for the trait that is automatically registered
 * each time the model is validated by implementing the
 * {@link Validator} interface and registering the validator through SPI.
 */
public interface Trait extends FromSourceLocation, ToNode {
    /**
     * Gets the fully qualified name of the trait.
     *
     * <p>All traits should contain a namespace. Namespaces, if come before
     * the trait name, separated by a "#" character. For example,
     * {@code foo.bar#baz}, is a valid trait name. Do not attempt to parse
     * the name and namespace yourself; use the {@link #getTraitNamespace()}
     * and {@link #getRelativeTraitName()} method to get specific parts
     * of the trait name.
     *
     * @return Returns the fully-qualified name of the trait.
     */
    String getTraitName();

    /**
     * Gets the namespace of the trait (e.g., {@code smithy.api}.
     *
     * <p>If the trait name has no namespace, then the default
     * "smithy.api" namespace is returned.
     *
     * @return Returns the namespace of the trait.
     */
    default String getTraitNamespace() {
        String name = getTraitName();
        int index = name.indexOf("#");
        return index == -1 ? Prelude.NAMESPACE : name.substring(0, index);
    }

    /**
     * Gets the name of the trait with no namespace
     * (e.g., "required" instead of "smithy.api#required").
     *
     * @return Returns the relative name of the trait.
     */
    default String getRelativeTraitName() {
        String name = getTraitName();
        int index = name.indexOf("#");
        if (index == -1) {
            return name;
        } else if (index == name.length()) {
            return "";
        } else {
            return name.substring(index + 1);
        }
    }

    /**
     * Used in a stream flatMapStream to return a {@link Stream} with a
     * {@link Pair} of Shape and Trait if the trait is present on the
     * given shape.
     *
     * @param shape Shape to query for the trait.
     * @param traitClass Trait to retrieve.
     * @param <S> Shape
     * @param <T> Trait
     * @return Returns the Stream of the found trait or an empty stream.
     */
    static <S extends Shape, T extends Trait> Stream<Pair<S, T>> flatMapStream(
            S shape,
            Class<T> traitClass
    ) {
        return OptionalUtils.stream(shape.getTrait(traitClass).map(t -> Pair.of(shape, t)));
    }

    /**
     * Gets the idiomatic name of a prelude trait by stripping off the
     * smithy.api# prefix. This is used in various error messages.
     *
     * @param traitName Trait name to make idiomatic.
     * @return Returns the idiomatic trait name.
     */
    static String getIdiomaticTraitName(String traitName) {
        return traitName.replace("smithy.api#", "");
    }

    /**
     * Makes the given trait name absolute if it is relative.
     *
     * <p>The namespace used to resolve with the trait name is the
     * prelude namespace, smithy.api.
     *
     * @param traitName Trait name to make absolute.
     * @return Returns the absolute trait name.
     */
    static String makeAbsoluteName(String traitName) {
        return makeAbsoluteName(traitName, Prelude.NAMESPACE);
    }

    /**
     * Makes the given trait name absolute if it is relative.
     *
     * @param traitName Trait name to make absolute.
     * @param defaultNamespace Namespace to use if the name is relative.
     * @return Returns the absolute trait name.
     */
    static String makeAbsoluteName(String traitName, String defaultNamespace) {
        return traitName.contains("#") ? traitName : defaultNamespace + "#" + traitName;
    }
}