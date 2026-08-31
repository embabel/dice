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
package com.embabel.dice.metamodel

import com.embabel.agent.core.DomainType

/**
 * Decides which domain types a [MetamodelVersion] stamp covers.
 *
 * A DICE domain is rarely all one thing. Part of it is closed-world: the types you have committed
 * to, whose shape you want to notice changing. The rest is open-world: exploratory types that
 * extraction proposes, that come and go, and that would otherwise break a version comparison every
 * time an LLM invents one. Governance is therefore per type and opt-in, in the spirit of
 * Hibernate's `@Version`.
 *
 * A selector is a predicate over types, so the usual form is a lambda over names you already hold:
 *
 * ```kotlin
 * val governed = setOf("Person", "Company")
 * val version = MetamodelVersion.from(dataDictionary, GovernedTypeSelector { it.name in governed })
 * ```
 *
 * Selecting a subset changes which types the stamp covers, and leaves the encoding alone. Adding an
 * ungoverned type to the dictionary leaves the content hash as it was, while touching a governed
 * one changes it.
 */
fun interface GovernedTypeSelector {

    /**
     * @param type A domain type from the dictionary being stamped.
     * @return `true` when this type is under version governance and belongs in the stamp.
     */
    fun governs(type: DomainType): Boolean

    companion object {

        /**
         * Governs every type in the dictionary. This is the whole-schema stamp, and what
         * [MetamodelVersion.from] uses when no selector is given.
         */
        @JvmField
        val ALL: GovernedTypeSelector = GovernedTypeSelector { true }
    }
}
