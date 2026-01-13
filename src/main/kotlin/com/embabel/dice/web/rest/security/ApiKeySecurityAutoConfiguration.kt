package com.embabel.dice.web.rest.security

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Configuration properties for DICE API key authentication.
 *
 * @property enabled Enable API key authentication (default: false)
 * @property keys List of valid API keys. For production, use a custom ApiKeyAuthenticator instead.
 * @property headerName Header name for the API key (default: X-API-Key)
 * @property pathPatterns Path patterns to protect (default: /api/v1/ and subpaths)
 */
@ConfigurationProperties(prefix = "dice.security.api-key")
data class DiceApiKeyProperties(
    val enabled: Boolean = false,
    val keys: List<String> = emptyList(),
    val headerName: String = ApiKeyAuthenticator.DEFAULT_HEADER_NAME,
    val pathPatterns: List<String> = listOf("/api/v1/**"),
)

/**
 * Auto-configuration for DICE API key security.
 *
 * Enable via application.yml with dice.security.api-key.enabled=true and
 * provide keys via dice.security.api-key.keys list.
 *
 * For production, provide your own ApiKeyAuthenticator bean instead of using
 * the in-memory keys list.
 */
@Configuration
@EnableConfigurationProperties(DiceApiKeyProperties::class)
@ConditionalOnProperty(prefix = "dice.security.api-key", name = ["enabled"], havingValue = "true")
class ApiKeySecurityAutoConfiguration {

    private val log = LoggerFactory.getLogger(ApiKeySecurityAutoConfiguration::class.java)

    /**
     * Default in-memory authenticator using configured keys.
     * Override by providing your own ApiKeyAuthenticator bean.
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyAuthenticator::class)
    fun apiKeyAuthenticator(properties: DiceApiKeyProperties): ApiKeyAuthenticator {
        if (properties.keys.isEmpty()) {
            log.warn("DICE API key security is enabled but no keys are configured!")
        } else {
            log.info("DICE API key security enabled with {} configured key(s)", properties.keys.size)
        }
        return InMemoryApiKeyAuthenticator(
            validApiKeys = properties.keys.toSet(),
            headerName = properties.headerName,
        )
    }

    /**
     * Filter registration for API key authentication.
     */
    @Bean
    fun apiKeyAuthenticationFilterRegistration(
        authenticator: ApiKeyAuthenticator,
        properties: DiceApiKeyProperties,
    ): FilterRegistrationBean<ApiKeyAuthenticationFilter> {
        val filter = ApiKeyAuthenticationFilter(
            authenticator = authenticator,
            pathPatterns = properties.pathPatterns,
        )

        return FilterRegistrationBean<ApiKeyAuthenticationFilter>().apply {
            this.filter = filter
            this.order = Ordered.HIGHEST_PRECEDENCE + 100
            this.urlPatterns = listOf("/*")
            log.info(
                "DICE API key filter registered for patterns: {}",
                properties.pathPatterns.joinToString(", ")
            )
        }
    }
}
