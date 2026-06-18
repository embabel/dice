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
package com.embabel.dice.web.rest.security

/**
 * Interface for API key authentication.
 * Implement this to provide custom API key validation logic.
 */
interface ApiKeyAuthenticator {

    /**
     * Validate an API key.
     *
     * @param apiKey The API key from the request header
     * @return AuthResult indicating success or failure
     */
    fun authenticate(apiKey: String): AuthResult

    /**
     * The header name to look for the API key.
     * Default is "X-API-Key".
     */
    val headerName: String get() = DEFAULT_HEADER_NAME

    companion object {
        const val DEFAULT_HEADER_NAME = "X-API-Key"
    }
}

/**
 * Result of API key authentication.
 */
sealed class AuthResult {
    /**
     * Authentication succeeded.
     */
    data class Authorized(
        val principal: String = "api-client",
        val metadata: Map<String, Any> = emptyMap(),
    ) : AuthResult()

    /**
     * Authentication failed.
     */
    data class Unauthorized(val reason: String) : AuthResult()
}

/**
 * Simple in-memory API key authenticator.
 * Validates against a set of configured API keys.
 *
 * For production, implement [ApiKeyAuthenticator] with database/vault lookup.
 */
class InMemoryApiKeyAuthenticator(
    private val validApiKeys: Set<String>,
    override val headerName: String = ApiKeyAuthenticator.DEFAULT_HEADER_NAME,
) : ApiKeyAuthenticator {

    override fun authenticate(apiKey: String): AuthResult {
        return if (apiKey in validApiKeys) {
            AuthResult.Authorized(principal = "api-client")
        } else {
            AuthResult.Unauthorized("Invalid API key")
        }
    }

    companion object {
        /**
         * Create authenticator from a single API key.
         */
        fun withKey(apiKey: String): InMemoryApiKeyAuthenticator =
            InMemoryApiKeyAuthenticator(setOf(apiKey))

        /**
         * Create authenticator from multiple API keys.
         */
        fun withKeys(vararg apiKeys: String): InMemoryApiKeyAuthenticator =
            InMemoryApiKeyAuthenticator(apiKeys.toSet())
    }
}
