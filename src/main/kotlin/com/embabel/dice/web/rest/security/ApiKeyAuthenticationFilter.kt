package com.embabel.dice.web.rest.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Servlet filter that validates API keys on incoming requests.
 *
 * This filter checks for an API key header and validates it using the
 * configured [ApiKeyAuthenticator]. Requests without a valid API key
 * receive a 401 Unauthorized response.
 *
 * For Spring Security integration, add this filter before UsernamePasswordAuthenticationFilter.
 */
class ApiKeyAuthenticationFilter(
    private val authenticator: ApiKeyAuthenticator,
    private val pathPatterns: List<String> = listOf("/api/v1/**"),
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI

        // Check if this path requires authentication
        if (!shouldAuthenticate(path)) {
            filterChain.doFilter(request, response)
            return
        }

        val apiKey = request.getHeader(authenticator.headerName)

        if (apiKey.isNullOrBlank()) {
            log.debug("Missing API key for path: {}", path)
            sendUnauthorized(response, "Missing API key header: ${authenticator.headerName}")
            return
        }

        when (val result = authenticator.authenticate(apiKey)) {
            is AuthResult.Authorized -> {
                log.debug("API key authenticated for path: {}, principal: {}", path, result.principal)
                // Store principal in request for downstream use
                request.setAttribute(PRINCIPAL_ATTRIBUTE, result.principal)
                request.setAttribute(AUTH_METADATA_ATTRIBUTE, result.metadata)
                filterChain.doFilter(request, response)
            }
            is AuthResult.Unauthorized -> {
                log.warn("Invalid API key for path: {}, reason: {}", path, result.reason)
                sendUnauthorized(response, result.reason)
            }
        }
    }

    private fun shouldAuthenticate(path: String): Boolean {
        return pathPatterns.any { pattern ->
            matchesPattern(path, pattern)
        }
    }

    private fun matchesPattern(path: String, pattern: String): Boolean {
        if (pattern.endsWith("/**")) {
            val prefix = pattern.dropLast(3)
            return path.startsWith(prefix)
        }
        if (pattern.endsWith("/*")) {
            val prefix = pattern.dropLast(2)
            return path.startsWith(prefix) && !path.substring(prefix.length).contains('/')
        }
        return path == pattern
    }

    private fun sendUnauthorized(response: HttpServletResponse, message: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error": "Unauthorized", "message": "$message"}""")
    }

    companion object {
        const val PRINCIPAL_ATTRIBUTE = "dice.auth.principal"
        const val AUTH_METADATA_ATTRIBUTE = "dice.auth.metadata"
    }
}
