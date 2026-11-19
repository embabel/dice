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
package com.embabel.dice.storage.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Which Drivine datasource DICE keeps its stores in, when DICE builds the Drivine beans itself.
 *
 * It sits under the same `embabel.dice.store` prefix as [DiceStoreProperties] but in a class of its
 * own, so that class keeps its exact shape.
 */
@ConfigurationProperties(prefix = "embabel.dice.store")
data class DiceDrivineProperties(
    /**
     * The name of a datasource under `database.datasources`. Leave it unset when there is only one;
     * set it when there are several, or startup stops and asks you to.
     */
    var datasource: String? = null,
)
