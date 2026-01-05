package com.embabel.dice;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates that ContextId and related classes can be used from Java code.
 * Note: ContextId is a Kotlin value class (inline class) which is exposed to Java
 * as the underlying String type.
 */
class ContextIdJavaInteropTest {

    @Test
    void canCreateSourceAnalysisContextWithStronglyTypedBuilder() {
        // Use the strongly-typed builder pattern for Java
        String contextId = "java-context-test";

        SourceAnalysisContext context = SourceAnalysisContext
            .withContextId(contextId)
            .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
            .withSchema(DataDictionary.fromClasses());

        assertNotNull(context);
        assertEquals(contextId, context.getContextIdValue());
    }

    @Test
    void canAddOptionalPropertiesAfterBuilding() {
        SourceAnalysisContext context = SourceAnalysisContext
            .withContextId("test-context")
            .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
            .withSchema(DataDictionary.fromClasses())
            .withKnownEntities(java.util.List.of())
            .withTemplateModel(java.util.Map.of("key", "value"));

        assertNotNull(context);
        assertTrue(context.getKnownEntities().isEmpty());
        assertEquals("value", context.getTemplateModel().get("key"));
    }
}
