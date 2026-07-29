/*
 * Compiled against the unmodified origin/main dice jar, then run with the candidate jar.
 *
 * LINKED proves an unchanged approved JVM constructor descriptor. Both constructor probes use
 * fully specified arguments so this fixture makes no claim about Kotlin's synthetic default
 * constructor ABI. NoSuchMethodError on the old copy descriptors is expected and deliberately
 * records, rather than broadens, the approved source/JSON/Java compatibility boundary.
 */
package com.embabel.dice.compat

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry

fun main() {
    val locator = ContentAddressedLocator("legacy-client")

    val provenance = probe("ProvenanceEntry.constructor.full") {
        ProvenanceEntry(locator, "chunk", 0, 5, "hash")
    }
    probe("ProvenanceEntry.constructor.nullable") {
        ProvenanceEntry(locator, null, null, null, null)
    }
    provenance?.let { entry ->
        probe("ProvenanceEntry.copy.direct") {
            entry.copy(locator, "chunk-2", 0, 7, "hash-2")
        }
        probe("ProvenanceEntry.copy.default") {
            entry.copy(contentHash = "hash-2")
        }
    }

    val context = probe("SourceAnalysisContext.constructor.full") {
        SourceAnalysisContext(
            DataDictionary.fromClasses("legacy-client"),
            AlwaysCreateEntityResolver,
            ContextId("legacy-client"),
            emptyList(),
            Relations.empty(),
            emptyMap(),
            null,
            null,
            false,
            emptyMap(),
        )
    }
    probe("SourceAnalysisContext.constructor.alternate") {
        SourceAnalysisContext(
            DataDictionary.fromClasses("legacy-client-defaults"),
            AlwaysCreateEntityResolver,
            ContextId("legacy-client-defaults"),
            emptyList(),
            Relations.empty(),
            emptyMap(),
            null,
            null,
            false,
            emptyMap(),
        )
    }
    context?.let { analysisContext ->
        probe("SourceAnalysisContext.copy.direct") {
            analysisContext.copy(
                analysisContext.schema,
                analysisContext.entityResolver,
                analysisContext.contextId,
                analysisContext.knownEntities,
                analysisContext.relations,
                analysisContext.promptVariables,
                analysisContext.sourceLocator,
                analysisContext.perspective,
                analysisContext.mintNewEntities,
                analysisContext.mintedEntityProperties,
            )
        }
        probe("SourceAnalysisContext.copy.default") {
            analysisContext.copy(promptVariables = mapOf("legacy" to true))
        }
    }
}

private fun <T> probe(name: String, call: () -> T): T? =
    try {
        call().also { println("$name=LINKED") }
    } catch (error: LinkageError) {
        println("$name=${error::class.java.simpleName}")
        null
    }
