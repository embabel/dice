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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls every entry point the way a Java consumer compiled against the previous release does.
 * <p>
 * Aliases and provenance were added as trailing parameters with defaults, so a Kotlin caller sees
 * no change. A Java caller sees whatever descriptors the compiler emitted, which is why
 * {@code @JvmOverloads} is on those constructors and factories: without it, adding a parameter
 * would delete the descriptor an already-compiled consumer is linked against, and the failure
 * would be a {@code NoSuchMethodError} at runtime rather than a compile error here.
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
    void theFourArgumentPropertySignatureConstructorStillExists() {
        PropertySignature signature = new PropertySignature(
                "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE);

        assertEquals("age", signature.getName());
        assertEquals(Set.of(), signature.getAliases());
    }

    @Test
    void thePropertySignatureConstructorAlsoTakesAliases() {
        PropertySignature signature = new PropertySignature(
                "emailAddress", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, Set.of("email"));

        assertEquals(Set.of("email"), signature.getAliases());
    }

    @Test
    void theFiveArgumentMetamodelVersionConstructorStillExists() {
        MetamodelVersion version = new MetamodelVersion(
                "test",
                List.of("Person"),
                Map.of("Person", Set.of("Person")),
                Map.of("Person", Set.of()),
                List.of());

        assertEquals(List.of("Person"), version.getEntityTypeNames());
        assertEquals(Map.of(), version.getEntityTypeAliases());
        assertNull(version.getOrigin());
        assertNull(version.getLastStamped());
    }

    @Test
    void theMetamodelVersionConstructorAlsoTakesAliasesAndProvenance() {
        MetamodelVersion version = new MetamodelVersion(
                "test",
                List.of("Person"),
                Map.of("Person", Set.of("Person")),
                Map.of("Person", Set.of()),
                List.of(),
                Map.of("Person", Set.of("Human")),
                new StampProvenance("deploy-pipeline", "release-1"),
                new StampProvenance("operator", null));

        assertEquals(Set.of("Human"), version.getEntityTypeAliases().get("Person"));
        assertEquals("deploy-pipeline", version.getOrigin().getActor());
        assertEquals("operator", version.getLastStamped().getActor());
        assertNull(version.getLastStamped().getTrigger());
    }

    @Test
    void theOneAndTwoArgumentStampingFactoriesStillExist() {
        MetamodelVersion whole = MetamodelVersion.from(goldenSchema());
        MetamodelVersion governed = MetamodelVersion.from(goldenSchema(), GovernedTypeSelector.ALL);

        assertEquals(whole.getContentHash(), governed.getContentHash());
        assertEquals(List.of("Company", "Person"), whole.getEntityTypeNames());
    }

    @Test
    void theStampingFactoryAlsoTakesAliases() {
        MetamodelVersion version = MetamodelVersion.from(
                goldenSchema(),
                GovernedTypeSelector.ALL,
                new SchemaAliases(Map.of("Person", Set.of("Human")), Map.of()));

        assertEquals(Set.of("Human"), version.getEntityTypeAliases().get("Person"));
    }

    @Test
    void theOneAndTwoArgumentDeclarationFactoriesStillExist() {
        DeclaredSchema whole = DeclaredSchema.from(goldenSchema());
        DeclaredSchema governed = DeclaredSchema.from(goldenSchema(), GovernedTypeSelector.ALL);

        assertEquals(whole, governed);
        assertEquals(Set.of(), whole.getRelationshipTypeNames());
    }

    @Test
    void theDeclarationFactoryAlsoTakesAliases() {
        DeclaredSchema declared = DeclaredSchema.from(
                goldenSchema(),
                GovernedTypeSelector.ALL,
                new SchemaAliases(Map.of("Person", Set.of("Human")), Map.of()));

        assertEquals(Set.of("Human"), declared.getVersion().getEntityTypeAliases().get("Person"));
    }

    @Test
    void theNoArgumentAliasAndProvenanceConstructorsExist() {
        assertEquals(Map.of(), new SchemaAliases().getTypeAliases());
        assertEquals(Map.of(), SchemaAliases.NONE.getPropertyAliases());
        assertNull(new StampProvenance().getActor());
        assertEquals("ci", new StampProvenance("ci").getActor());
    }

    @Test
    void theCollectionsAStampHandsBackRefuseMutationFromJava() {
        MetamodelVersion version = MetamodelVersion.from(
                goldenSchema(),
                GovernedTypeSelector.ALL,
                new SchemaAliases(Map.of("Person", Set.of("Human")), Map.of()));

        assertTrue(throwsOnMutation(() -> version.getEntityTypeAliases().remove("Person")));
        assertTrue(throwsOnMutation(() -> version.getEntityTypeAliases().get("Person").add("Sneaky")));
    }

    @Test
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
