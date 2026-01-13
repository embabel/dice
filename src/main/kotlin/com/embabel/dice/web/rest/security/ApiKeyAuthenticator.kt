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
