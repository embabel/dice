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
package com.embabel.dice.provenance

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * A reference to where source material lives.
 *
 * A [SourceLocator] holds a reference to source material rather than the material
 * itself. It tells a consumer how to locate or re-fetch the original material
 * that grounds a proposition, without assuming responsibility for storage,
 * credentials, or connector access.
 *
 * Implementations form a discriminated union, allowing consumers to match on the
 * kind of reference: URI, file path, content hash, or connector reference.
 *
 * @property display Optional human-readable label for UIs and reports
 *   (e.g. "Meeting notes 2026-05-21"). Never used for identity.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(UriLocator::class, name = "uri"),
    JsonSubTypes.Type(FileLocator::class, name = "file"),
    JsonSubTypes.Type(ContentAddressedLocator::class, name = "content"),
    JsonSubTypes.Type(ConnectorRef::class, name = "connector"),
)
sealed interface SourceLocator {

    /**
     * Optional human-readable label for display in UIs and reports.
     * This is presentation-only and never participates in identity.
     */
    val display: String?

    /**
     * A stable, comparable key identifying this locator.
     *
     * Keys are prefixed by locator kind so that distinct kinds never collide
     * (`uri:...`, `file:...`, `content:...`, `connector:...`). Use this for
     * deduplication, indexing, and reference equality rather than the [display]
     * label.
     *
     * @return a stable key string for this locator
     */
    fun key(): String
}

/**
 * A locator that points at a resource by URI (e.g. `https://...`, `s3://...`).
 *
 * @property uri The URI referencing the source resource
 * @property display Optional human-readable label
 */
data class UriLocator @JvmOverloads constructor(
    val uri: String,
    override val display: String? = null,
) : SourceLocator {

    init {
        require(uri.isNotBlank()) { "uri must not be blank" }
    }

    override fun key(): String = "uri:$uri"

    // Identity is [key]; [display] is presentation-only and excluded (see SourceLocator contract).
    override fun equals(other: Any?): Boolean = other is SourceLocator && other.key() == key()

    override fun hashCode(): Int = key().hashCode()
}

/**
 * A locator that points at a resource by file path.
 *
 * Note that raw paths are environment-specific; prefer [ContentAddressedLocator]
 * for shareable outputs where a path would not be portable.
 *
 * @property path The file system path referencing the source resource
 * @property display Optional human-readable label
 */
data class FileLocator @JvmOverloads constructor(
    val path: String,
    override val display: String? = null,
) : SourceLocator {

    init {
        require(path.isNotBlank()) { "path must not be blank" }
    }

    override fun key(): String = "file:$path"

    // Identity is [key]; [display] is presentation-only and excluded (see SourceLocator contract).
    override fun equals(other: Any?): Boolean = other is SourceLocator && other.key() == key()

    override fun hashCode(): Int = key().hashCode()
}

/**
 * A locator that identifies source material by its content hash.
 *
 * Because it identifies material by content rather than location, any store
 * holding matching bytes satisfies the reference.
 *
 * @property contentHash The hash of the source content (e.g. a SHA-256 hex string)
 * @property display Optional human-readable label
 */
data class ContentAddressedLocator @JvmOverloads constructor(
    val contentHash: String,
    override val display: String? = null,
) : SourceLocator {

    init {
        require(contentHash.isNotBlank()) { "contentHash must not be blank" }
    }

    override fun key(): String = "content:$contentHash"

    // Identity is [key]; [display] is presentation-only and excluded (see SourceLocator contract).
    override fun equals(other: Any?): Boolean = other is SourceLocator && other.key() == key()

    override fun hashCode(): Int = key().hashCode()
}

/**
 * A locator that references material owned by an external connector.
 *
 * The connector (a private/product concern) knows how to resolve
 * [externalId] within the system identified by [connectorId]. DICE only holds
 * the identifiers needed to ask the connector for the material later.
 *
 * @property connectorId Identifier of the connector that owns the material
 *   (e.g. "slack", "notion", "gmail")
 * @property externalId Identifier of the material within that connector
 * @property display Optional human-readable label
 */
data class ConnectorRef @JvmOverloads constructor(
    val connectorId: String,
    val externalId: String,
    override val display: String? = null,
) : SourceLocator {

    init {
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        require(externalId.isNotBlank()) { "externalId must not be blank" }
    }

    override fun key(): String = "connector:$connectorId:$externalId"

    // Identity is [key]; [display] is presentation-only and excluded (see SourceLocator contract).
    override fun equals(other: Any?): Boolean = other is SourceLocator && other.key() == key()

    override fun hashCode(): Int = key().hashCode()
}
