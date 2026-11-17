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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

// These tests keep PropositionDraft in step with the Proposition constructor, so a new field
// on Proposition cannot be left out of the draft without a test failing.
//
// a: the draft's public properties are the constructor's parameters, apart from contextIdValue,
//    which is only a Java convenience.
// b: every constructor parameter except contextId has a public with step on the draft.
// c: a draft built with only the three required values has the same non-time, non-id values as
//    a proposition built with the constructor's defaults.
class PropositionDraftParityTest {

    private val constructorNames: Set<String?> =
        Proposition::class.primaryConstructor!!.parameters.map { it.name }.toSet()

    @Test
    fun a_draftPropertiesMatchTheConstructorParameters() {
        val draftNames = PropositionDraft::class.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .toSet() - "contextIdValue"

        val missing = constructorNames - draftNames
        assertEquals(
            constructorNames,
            draftNames,
            "The draft has no public property for these constructor parameters: $missing",
        )
    }

    @Test
    fun b_everyConstructorParameterHasAWithStep() {
        val stepNames = PropositionDraft::class.memberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .toSet()

        val missingSteps = constructorNames
            .filter { it != null && it != "contextId" }
            .map { stepFor(it!!) }
            .filter { it !in stepNames }

        assertTrue(missingSteps.isEmpty(), "The draft has no public step for: $missingSteps")
    }

    @Test
    fun c_draftDefaultsMatchTheConstructorDefaults() {
        val fromDraft = Proposition.draft("acme", "t", 0.9).toProposition()
        val fromConstructor = Proposition(
            contextId = ContextId("acme"),
            text = "t",
            mentions = emptyList(),
            confidence = 0.9,
        )

        val skipped = setOf("id", "created", "contentRevised", "metadataRevised", "lastAccessed")
        val compared = Proposition::class.memberProperties
            .filter { it.name in constructorNames && it.name !in skipped }

        for (property in compared) {
            assertEquals(
                property.getter.call(fromConstructor),
                property.getter.call(fromDraft),
                "Draft default for ${property.name} differs from the constructor default",
            )
        }
    }

    /** The name of the with step for a constructor parameter. */
    private fun stepFor(parameter: String): String =
        if (parameter == "provenanceEntries") {
            "withProvenance"
        } else {
            "with" + parameter.replaceFirstChar(Char::uppercaseChar)
        }
}
