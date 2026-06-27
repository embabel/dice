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

import com.embabel.dice.metamodel.support.JaversMetamodelDiffer
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class MetamodelConfigurationTest {

    private val config = MetamodelConfiguration()

    @Test
    fun `provides a JaVers-backed differ by default`() {
        assertInstanceOf(JaversMetamodelDiffer::class.java, config.metamodelDiffer())
    }

    @Test
    fun `provides the mention-type drift quarantine policy by default`() {
        assertInstanceOf(MentionTypeDriftQuarantinePolicy::class.java, config.driftQuarantinePolicy())
    }
}
