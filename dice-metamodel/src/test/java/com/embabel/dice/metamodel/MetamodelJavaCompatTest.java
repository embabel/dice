/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.dice.metamodel;

import com.embabel.agent.core.Cardinality;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.core.DomainType;
import com.embabel.agent.core.DynamicType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls every entry point the way a Java consumer compiled against the previous release does.
 * <p>
 * Aliases were added as trailing parameters with defaults, so a Kotlin caller sees no change. A
 * Java caller sees whatever descriptors the compiler emitted, which is why {@code @JvmOverloads} is
 * on those constructors and factories: without it, adding a parameter would delete the descriptor
 * an already-compiled consumer is linked against, and the failure would be a
 * {@code NoSuchMethodError} at runtime rather than a compile error here.
 */
class MetamodelJavaCompatTest {

    /** DynamicType has no Java-friendly overloads upstream, so every argument is spelled out. */
    private static DomainType type(String name) {
        return new DynamicType(name, "", List.of(), List.of(), true);
    }

    private static DataDictionary goldenSchema() {
        return DataDictionary.fromDomainTypes("golden-schema", List.of(type("Person"), type("Company")));
    }

    @Test
    @DisplayName("the four-argument property signature constructor still exists")
    void theFourArgumentPropertySignatureConstructorStillExists() {
        PropertySignature signature = new PropertySignature(
                "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE);

        assertEquals("age", signature.getName());
        assertEquals(Set.of(), signature.getAliases());
    }

    @Test
    @DisplayName("the property signature constructor also takes aliases")
    void thePropertySignatureConstructorAlsoTakesAliases() {
        PropertySignature signature = new PropertySignature(
                "emailAddress", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, Set.of("email"));

        assertEquals(Set.of("email"), signature.getAliases());
    }

    @Test
    @DisplayName("the five-argument metamodel version constructor still exists")
    void theFiveArgumentMetamodelVersionConstructorStillExists() {
        MetamodelVersion version = new MetamodelVersion(
                "test",
                List.of("Person"),
                Map.of("Person", Set.of("Person")),
                Map.of("Person", Set.of()),
                List.of());

        assertEquals(List.of("Person"), version.getEntityTypeNames());
        assertEquals(Map.of(), version.getEntityTypeAliases());
    }

    @Test
    @DisplayName("the metamodel version constructor also takes aliases")
    void theMetamodelVersionConstructorAlsoTakesAliases() {
        MetamodelVersion version = new MetamodelVersion(
                "test",
                List.of("Person"),
                Map.of("Person", Set.of("Person")),
                Map.of("Person", Set.of()),
                List.of(),
                Map.of("Person", Set.of("Human")));

        assertEquals(Set.of("Human"), version.getEntityTypeAliases().get("Person"));
    }

    @Test
    @DisplayName("the one and two-argument stamping factories still exist")
    void theOneAndTwoArgumentStampingFactoriesStillExist() {
        MetamodelVersion whole = MetamodelVersion.from(goldenSchema());
        MetamodelVersion governed = MetamodelVersion.from(goldenSchema(), GovernedTypeSelector.ALL);

        assertEquals(whole.getContentHash(), governed.getContentHash());
        assertEquals(List.of("Company", "Person"), whole.getEntityTypeNames());
    }

    @Test
    @DisplayName("the stamping factory also takes aliases")
    void theStampingFactoryAlsoTakesAliases() {
        MetamodelVersion version = MetamodelVersion.from(
                goldenSchema(),
                GovernedTypeSelector.ALL,
                new SchemaAliases(Map.of("Person", Set.of("Human")), Map.of()));

        assertEquals(Set.of("Human"), version.getEntityTypeAliases().get("Person"));
    }

    @Test
    @DisplayName("the one and two-argument declaration factories still exist")
    void theOneAndTwoArgumentDeclarationFactoriesStillExist() {
        DeclaredSchema whole = DeclaredSchema.from(goldenSchema());
        DeclaredSchema governed = DeclaredSchema.from(goldenSchema(), GovernedTypeSelector.ALL);

        assertEquals(whole, governed);
        assertEquals(Set.of(), whole.getRelationshipTypeNames());
    }

    @Test
    @DisplayName("the declaration factory also takes aliases")
    void theDeclarationFactoryAlsoTakesAliases() {
        DeclaredSchema declared = DeclaredSchema.from(
                goldenSchema(),
                GovernedTypeSelector.ALL,
                new SchemaAliases(Map.of("Person", Set.of("Human")), Map.of()));

        assertEquals(Set.of("Human"), declared.getVersion().getEntityTypeAliases().get("Person"));
    }

    @Test
    @DisplayName("the no-argument alias constructor exists")
    void theNoArgumentAliasConstructorExists() {
        assertEquals(Map.of(), new SchemaAliases().getTypeAliases());
        assertEquals(Map.of(), SchemaAliases.NONE.getPropertyAliases());
    }

    @Test
    @DisplayName("the collections a stamp hands back refuse mutation from Java")
    void theCollectionsAStampHandsBackRefuseMutationFromJava() {
        MetamodelVersion version = MetamodelVersion.from(
                goldenSchema(),
                GovernedTypeSelector.ALL,
                new SchemaAliases(Map.of("Person", Set.of("Human")), Map.of()));

        assertTrue(throwsOnMutation(() -> version.getEntityTypeAliases().remove("Person")));
        assertTrue(throwsOnMutation(() -> version.getEntityTypeAliases().get("Person").add("Sneaky")));
    }

    @Test
    @DisplayName("the shipped Kotlin default synthetic keeps its descriptor")
    void theShippedKotlinDefaultSyntheticKeepsItsDescriptor() throws Exception {
        // A Kotlin caller that omits a defaulted argument links against the $default synthetic
        // rather than the function itself. DeclaredSchema.from shipped with one defaulted
        // parameter, so that synthetic is part of the module's binary surface. Adding a third
        // defaulted parameter would have rewritten its descriptor and turned every already
        // compiled `DeclaredSchema.from(dictionary)` into a NoSuchMethodError, which is why
        // SchemaAliases arrives on a separate overload that requires it.
        Class<?> companion = Class.forName("com.embabel.dice.metamodel.DeclaredSchema$Companion");

        assertNotNull(companion.getDeclaredMethod(
                "from$default",
                companion,
                DataDictionary.class,
                GovernedTypeSelector.class,
                int.class,
                Object.class));
    }

    @Test
    @DisplayName("the fluent stamping chain reads the same from Java")
    void theFluentStampingChainReadsTheSameFromJava() {
        SchemaAliases aliases = new SchemaAliases(
                Map.of(), Map.of("Person", Map.of("age", Set.of("years"))));

        MetamodelVersion chained = MetamodelVersion.stamping(goldenSchema())
                .governedBy(Set.of("Person"))
                .withAliases(aliases)
                .stamp();

        assertEquals(List.of("Person"), chained.getEntityTypeNames());
        assertEquals(
                MetamodelVersion.from(goldenSchema(), type -> type.getName().equals("Person"), aliases),
                chained);
    }

    @Test
    @DisplayName("a stamping finishes as a declaration from Java too")
    void aStampingFinishesAsADeclarationFromJava() {
        DeclaredSchema declared = MetamodelVersion.stamping(goldenSchema())
                .governedBy(Set.of("Person"))
                .declare();

        assertEquals(List.of("Person"), declared.getVersion().getEntityTypeNames());
        assertEquals(DeclaredSchema.from(goldenSchema(), type -> type.getName().equals("Person")), declared);
    }

    @Test
    @DisplayName("a stamping step hands back a new value and leaves the old one alone")
    void aStampingStepHandsBackANewValueAndLeavesTheOldOneAlone() {
        MetamodelStamping base = MetamodelVersion.stamping(goldenSchema());

        MetamodelStamping narrowed = base.governedBy(Set.of("Person"));

        assertNotSame(base, narrowed);
        assertEquals(List.of("Company", "Person"), base.stamp().getEntityTypeNames());
        assertEquals(List.of("Person"), narrowed.stamp().getEntityTypeNames());
    }

    @Test
    @DisplayName("the stamping constructor keeps a one-argument arity for Java")
    void theStampingConstructorKeepsAOneArgumentArityForJava() throws Exception {
        // @JvmOverloads on the data class constructor is what generates the shorter arities. A
        // Java caller that builds a stamping directly links against this one, so losing it is a
        // NoSuchMethodError rather than a compile error here.
        assertNotNull(MetamodelStamping.class.getDeclaredConstructor(DataDictionary.class));

        MetamodelStamping stamping = new MetamodelStamping(goldenSchema());

        assertEquals(GovernedTypeSelector.ALL, stamping.getGovernedTypes());
        assertEquals(SchemaAliases.NONE, stamping.getAliases());
    }

    @Test
    @DisplayName("the stamping factories take no defaulted parameters")
    void theStampingFactoriesTakeNoDefaultedParameters() throws Exception {
        // MetamodelVersion.from shipped as two overloads with no defaults, so it has no $default
        // synthetic to preserve. Keeping it that way means the alias overload can never widen one.
        Class<?> companion = Class.forName("com.embabel.dice.metamodel.MetamodelVersion$Companion");

        long defaultSynthetics = Arrays.stream(companion.getDeclaredMethods())
                .filter(method -> method.getName().equals("from$default"))
                .count();

        assertEquals(0, defaultSynthetics);
    }

    private static boolean throwsOnMutation(Runnable mutation) {
        try {
            mutation.run();
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }
}
