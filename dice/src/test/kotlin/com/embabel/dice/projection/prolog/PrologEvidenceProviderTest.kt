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
package com.embabel.dice.projection.prolog

import com.embabel.dice.evidence.Evidence
import com.embabel.dice.evidence.RuleDeclaration
import com.embabel.dice.evidence.RuleGoal
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class PrologEvidenceProviderTest {

    private val canHelpRule = "can_help(X, Topic) :- expert_in(X, Topic)."

    // The coworker rule exactly as dice-rules.pl writes it.
    private val coworkerRule = "coworker(X, Y) :- works_at(X, Company), works_at(Y, Company), X \\= Y."

    // The three can_consult clauses as dice-rules.pl writes them.
    private val canConsultRules = """
        can_consult(Person, Expert, Topic) :-
            friend_of(Person, Expert),
            expert_in(Expert, Topic).
        can_consult(Person, Expert, Topic) :-
            colleague_of(Person, Expert),
            expert_in(Expert, Topic).
        can_consult(Person, Expert, Topic) :-
            coworker(Person, Expert),
            expert_in(Expert, Topic).
    """.trimIndent()

    private val canHelp = RuleDeclaration.of("can_help(X, Topic)").withInput("expert_in(X, Topic)")

    private val coworker = RuleDeclaration.of("coworker(X, Y)")
        .withInput("works_at(X, C)")
        .withInput("works_at(Y, C)")
        .withGuard("X \\= Y")

    private val consultFriend = RuleDeclaration.of("can_consult(Person, Expert, Topic)")
        .withInput("friend_of(Person, Expert)")
        .withInput("expert_in(Expert, Topic)")

    // The coworker clause calls coworker/2, which is itself a rule, so its declaration names the facts underneath.
    private val consultCoworker = RuleDeclaration.of("can_consult(Person, Expert, Topic)")
        .withInput("works_at(Person, Company)")
        .withInput("works_at(Expert, Company)")
        .withInput("expert_in(Expert, Topic)")
        .withGuard("Person \\= Expert")

    private val baseFacts = listOf(
        fact("expert_in", "alice", "kubernetes", confidence = 0.9, ids = listOf("prop-1")),
        fact("works_at", "alice", "techcorp", confidence = 0.85, ids = listOf("prop-2")),
        fact("works_at", "bob", "techcorp", confidence = 0.7, ids = listOf("prop-3")),
        fact("expert_in", "bob", "kotlin", confidence = 0.8, ids = listOf("prop-4")),
        fact("friend_of", "carol", "bob", confidence = 0.95, ids = listOf("prop-5")),
        fact("colleague_of", "dave", "bob", confidence = 0.6, ids = listOf("prop-6")),
    )

    private val allRules = listOf(canHelpRule, coworkerRule, canConsultRules).joinToString("\n")

    private fun fact(predicate: String, vararg args: String, confidence: Double, ids: List<String>) =
        PrologFact(predicate, args.toList(), confidence, sourcePropositionIds = ids)

    private fun projection(facts: List<PrologFact>) = PrologProjectionResult(
        facts = facts,
        confidenceFacts = facts.map { ConfidenceFact(it) },
        groundingFacts = facts.flatMap { f -> f.sourcePropositionIds.map { GroundingFact(f, it) } },
    )

    private fun provider(facts: List<PrologFact> = baseFacts, rules: String = allRules) =
        PrologEvidenceProvider.of(projection(facts), PrologSchema.withRules(rules))

    private fun proposition(id: String, text: String, confidence: Double, age: Duration = Duration.ZERO) =
        Proposition.draft("ctx-main", text, confidence)
            .withId(id)
            .withDecay(if (age.isZero) 0.0 else 0.5)
            .withCreated(Instant.now().minus(age))
            .withContentRevised(Instant.now().minus(age))
            .withMetadataRevised(Instant.now().minus(age))
            .withLastAccessed(Instant.now().minus(age))
            .toProposition()

    private fun only(conclusions: List<Evidence.Derived>): Evidence.Derived {
        assertEquals(1, conclusions.size, "expected one conclusion")
        return conclusions.single()
    }

    @Nested
    inner class RuleIdentity {

        @Test
        fun `rule identity names the predicate and arity, the goal, its bindings and a theory hash`() {
            val derived = only(provider().withDeclaration(canHelp).conclusions(RuleGoal.of("can_help('alice', T)")))

            assertEquals("can_help('alice', 'kubernetes')", derived.conclusion)
            assertEquals("prolog", derived.rule.method)
            assertEquals("can_help/2", derived.rule.ruleId)
            assertEquals("can_help('alice', T)", derived.rule.expression)
            assertEquals("kubernetes", derived.rule.parameters["binding.T"])
            assertEquals(setOf("binding.T", "theory"), derived.rule.parameters.keys)
            assertTrue(Regex("[0-9a-f]{16}").matches(derived.rule.parameters.getValue("theory")))
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `the theory hash is the same for the same theory and changes when a rule changes`() {
            val goal = RuleGoal.of("can_help('alice', T)")
            val first = only(provider().withDeclaration(canHelp).conclusions(goal))
            val again = only(provider().withDeclaration(canHelp).conclusions(goal))
            val changed = only(
                provider(rules = "$allRules\ncan_help(X, Topic) :- works_at(X, Topic).")
                    .withDeclaration(canHelp)
                    .conclusions(goal)
            )

            assertEquals(first.rule.parameters["theory"], again.rule.parameters["theory"])
            assertNotEquals(first.rule.parameters["theory"], changed.rule.parameters["theory"])
        }
    }

    @Nested
    inner class DeclaredInputs {

        @Test
        fun `inputs are declared per clause and each declared clause traces its own facts`() {
            // alice can consult carol as a friend and bob as a coworker. The person is bound because the
            // tuProlog engine misses coworker/2 answers when its first argument starts out unbound.
            val facts = baseFacts + listOf(
                fact("friend_of", "alice", "carol", confidence = 0.95, ids = listOf("prop-10")),
                fact("expert_in", "carol", "prolog", confidence = 0.75, ids = listOf("prop-11")),
            )
            val conclusions = provider(facts)
                .withDeclaration(consultFriend)
                .withDeclaration(consultCoworker)
                .conclusions(RuleGoal.of("can_consult('alice', E, T)"))

            assertEquals(
                listOf("can_consult('alice', 'carol', 'prolog')", "can_consult('alice', 'bob', 'kotlin')"),
                conclusions.map { it.conclusion },
            )
            assertEquals(listOf("prop-10", "prop-11"), conclusions[0].inputIds)
            assertEquals(listOf("prop-2", "prop-3", "prop-4"), conclusions[1].inputIds)
            assertEquals("can_consult/3", conclusions[1].rule.ruleId)
        }

        @Test
        fun `coworker cites both works_at facts and takes the lower confidence`() {
            val derived = only(provider().withDeclaration(coworker).conclusions(RuleGoal.of("coworker('alice', Y)")))

            assertEquals("coworker('alice', 'bob')", derived.conclusion)
            assertEquals(listOf("prop-2", "prop-3"), derived.inputIds)
            assertEquals(0.7, derived.confidence)
        }

        @Test
        fun `a provable solution whose clause is not declared is left out`() {
            // dave can consult bob through the colleague clause, which has no declaration here.
            val provider = provider().withDeclaration(consultFriend).withDeclaration(consultCoworker)
            val goal = RuleGoal.of("can_consult('dave', 'bob', T)")

            assertTrue(provider.conclusions(goal).isEmpty())
            assertInstanceOf(Evidence.Missing::class.java, provider.evidence(goal))
        }

        @Test
        fun `a solution whose inputs match no recorded fact is left out`() {
            // coworker/2 is a rule, not a recorded fact, so nothing can be cited for it.
            val viaRule = RuleDeclaration.of("can_consult(Person, Expert, Topic)")
                .withInput("coworker(Person, Expert)")
                .withInput("expert_in(Expert, Topic)")
            val goal = RuleGoal.of("can_consult('alice', 'bob', T)")

            assertTrue(provider().withDeclaration(viaRule).conclusions(goal).isEmpty())
        }

        @Test
        fun `the next declaration is tried when an input matches no fact with source ids`() {
            val facts = baseFacts + fact("friend_of", "alice", "bob", confidence = 0.95, ids = emptyList())
            val derived = only(
                provider(facts)
                    .withDeclaration(consultFriend)
                    .withDeclaration(consultCoworker)
                    .conclusions(RuleGoal.of("can_consult('alice', 'bob', T)"))
            )

            assertEquals(listOf("prop-2", "prop-3", "prop-4"), derived.inputIds)
        }

        @Test
        fun `a guard filters solutions but is never cited`() {
            // Without a guard of its own, this rule also proves alice works with herself.
            val rules = "same_company(X, Y) :- works_at(X, C), works_at(Y, C)."
            val guarded = RuleDeclaration.of("same_company(X, Y)")
                .withInput("works_at(X, C)")
                .withInput("works_at(Y, C)")
                .withGuard("X \\= Y")
            val unguarded = RuleDeclaration.of("same_company(X, Y)")
                .withInput("works_at(X, C)")
                .withInput("works_at(Y, C)")
            val goal = RuleGoal.of("same_company('alice', Y)")

            val derived = only(provider(rules = rules).withDeclaration(guarded).conclusions(goal))
            assertEquals("same_company('alice', 'bob')", derived.conclusion)
            assertEquals(listOf("prop-2", "prop-3"), derived.inputIds)

            assertEquals(2, provider(rules = rules).withDeclaration(unguarded).conclusions(goal).size)
        }

        @Test
        fun `inputs are matched to facts by quoteAtom-normalised arguments, which can merge ids`() {
            val facts = listOf(
                fact("expert_in", "Alice-Smith", "K8s", confidence = 0.9, ids = listOf("prop-a")),
                fact("expert_in", "alice smith", "k8s", confidence = 0.8, ids = listOf("prop-b")),
            )
            val derived = only(
                provider(facts).withDeclaration(canHelp).conclusions(RuleGoal.of("can_help('alice_smith', T)"))
            )

            assertEquals("can_help('alice_smith', 'k8s')", derived.conclusion)
            assertEquals(listOf("prop-a", "prop-b"), derived.inputIds)
            assertEquals(0.8, derived.confidence)
        }
    }

    @Nested
    inner class NoDeclaration {

        @Test
        fun `a goal with no declaration returns Missing without running a query`() {
            // Asking loop/1 would never finish, so a result at all shows no query ran.
            val provider = provider(rules = "$allRules\nloop(X) :- loop(X).").withDeclaration(canHelp)

            val evidence = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier {
                provider.evidence(RuleGoal.of("loop('alice')"))
            })
            assertInstanceOf(Evidence.Missing::class.java, evidence)
        }

        @Test
        fun `recursive rules are not supported and are never run without a declaration`() {
            val provider = provider(rules = "$allRules\nloop(X) :- loop(X).")

            val conclusions = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier {
                provider.conclusions(RuleGoal.of("loop(X)"))
            })
            assertTrue(conclusions.isEmpty())
        }

        @Test
        fun `a declaration with the same name but another arity does not count`() {
            val provider = provider(rules = "$allRules\ncan_help(X) :- expert_in(X, _).").withDeclaration(canHelp)

            assertTrue(provider.conclusions(RuleGoal.of("can_help('alice')")).isEmpty())
        }
    }

    @Nested
    inner class LimitAndOrder {

        private val experts = listOf(
            fact("expert_in", "alice", "kubernetes", confidence = 0.9, ids = listOf("prop-1")),
            fact("expert_in", "bob", "kotlin", confidence = 0.8, ids = listOf("prop-4")),
            fact("expert_in", "carol", "prolog", confidence = 0.7, ids = listOf("prop-7")),
        )

        @Test
        fun `limit caps the conclusions returned`() {
            val provider = provider(experts).withDeclaration(canHelp)

            assertEquals(3, provider.conclusions(RuleGoal.of("can_help(X, T)")).size)
            assertEquals(
                listOf("can_help('alice', 'kubernetes')", "can_help('bob', 'kotlin')"),
                provider.conclusions(RuleGoal.of("can_help(X, T)").withLimit(2)).map { it.conclusion },
            )
        }

        @Test
        fun `conclusions are deduplicated by ground goal and the first proof wins`() {
            // alice can consult bob both as a friend and as a coworker: two proofs of one ground goal.
            val facts = baseFacts + fact("friend_of", "alice", "bob", confidence = 0.95, ids = listOf("prop-8"))
            val goal = RuleGoal.of("can_consult('alice', 'bob', T)")

            val friendFirst = only(
                provider(facts).withDeclaration(consultFriend).withDeclaration(consultCoworker).conclusions(goal)
            )
            val coworkerFirst = only(
                provider(facts).withDeclaration(consultCoworker).withDeclaration(consultFriend).conclusions(goal)
            )

            assertEquals(listOf("prop-8", "prop-4"), friendFirst.inputIds)
            assertEquals(listOf("prop-2", "prop-3", "prop-4"), coworkerFirst.inputIds)
        }

        @Test
        fun `evidence returns the first conclusion`() {
            val provider = provider().withDeclaration(consultFriend).withDeclaration(consultCoworker)
            val goal = RuleGoal.of("can_consult(P, 'bob', T)")

            assertEquals(provider.conclusions(goal).first(), provider.evidence(goal))
        }
    }

    @Nested
    inner class NotProvable {

        @Test
        fun `not provable gives Missing naming the goal`() {
            val provider = provider().withDeclaration(canHelp)
            val goal = RuleGoal.of("can_help('zed', T)")

            val missing = assertInstanceOf(Evidence.Missing::class.java, provider.evidence(goal))
            assertEquals("can_help('zed', T)", missing.searched.query)
            assertTrue(missing.searched.contextIdValues.isEmpty())
            assertTrue(provider.conclusions(goal).isEmpty())
        }

        @Test
        fun `not provable gives Missing naming the goal and its context when set`() {
            val provider = provider().withDeclaration(canHelp)

            val missing = assertInstanceOf(
                Evidence.Missing::class.java,
                provider.evidence(RuleGoal.of("can_help('zed', T)").withContext("ctx-main")),
            )
            assertEquals("can_help('zed', T)", missing.searched.query)
            assertEquals(setOf("ctx-main"), missing.searched.contextIdValues)
        }
    }

    @Nested
    inner class SourceIds {

        @Test
        fun `blank source ids are filtered out`() {
            val facts = listOf(fact("expert_in", "alice", "kubernetes", confidence = 0.9, ids = listOf(" ", "prop-1", "")))

            val derived = only(provider(facts).withDeclaration(canHelp).conclusions(RuleGoal.of("can_help('alice', T)")))
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a solution left with no source ids is not returned`() {
            val facts = listOf(fact("expert_in", "alice", "kubernetes", confidence = 0.9, ids = listOf("", " ")))
            val provider = provider(facts).withDeclaration(canHelp)
            val goal = RuleGoal.of("can_help('alice', T)")

            assertTrue(provider.conclusions(goal).isEmpty())
            assertInstanceOf(Evidence.Missing::class.java, provider.evidence(goal))
        }

        @Test
        fun `a matched fact with no source ids contributes nothing`() {
            // The second copy has no ids, so its low confidence must not count.
            val facts = listOf(
                fact("expert_in", "alice", "kubernetes", confidence = 0.9, ids = listOf("prop-1")),
                fact("expert_in", "alice", "kubernetes", confidence = 0.1, ids = emptyList()),
            )

            val derived = only(provider(facts).withDeclaration(canHelp).conclusions(RuleGoal.of("can_help('alice', T)")))
            assertEquals(listOf("prop-1"), derived.inputIds)
            assertEquals(0.9, derived.confidence)
        }

        @Test
        fun `input ids are the distinct source ids of the matched facts in order`() {
            val facts = listOf(
                fact("works_at", "alice", "techcorp", confidence = 0.85, ids = listOf("prop-9", "prop-2")),
                fact("works_at", "bob", "techcorp", confidence = 0.7, ids = listOf("prop-9")),
            )

            val derived = only(provider(facts, coworkerRule).withDeclaration(coworker).conclusions(RuleGoal.of("coworker('alice', Y)")))
            assertEquals(listOf("prop-9", "prop-2"), derived.inputIds)
        }
    }

    @Nested
    inner class Confidence {

        private val yearOld = Duration.of(365, ChronoUnit.DAYS)

        @Test
        fun `without a repository the fact's own confidence is used and inputs stay empty`() {
            val derived = only(provider().withDeclaration(coworker).conclusions(RuleGoal.of("coworker('alice', Y)")))

            assertEquals(0.7, derived.confidence)
            assertTrue(derived.inputs.isEmpty())
        }

        @Test
        fun `confidence is the lowest input at its stored decayed confidence`() {
            val old = proposition("prop-2", "Alice works at TechCorp", 0.85, age = yearOld)
            val fresh = proposition("prop-3", "Bob works at TechCorp", 0.7)
            val repository = InMemoryPropositionRepository().apply { save(old); save(fresh) }

            val derived = only(
                provider().withDeclaration(coworker).withRepository(repository)
                    .conclusions(RuleGoal.of("coworker('alice', Y)"))
            )

            assertTrue(derived.confidence < 0.7, "the year-old claim should have decayed below the fresh one")
            assertEquals(old.effectiveConfidence(), derived.confidence, 1e-9)
            assertEquals(listOf("prop-2", "prop-3"), derived.inputs.map { it.citation.propositionId })
        }

        @Test
        fun `a fact counts at the highest decayed confidence among its stored sources`() {
            val facts = listOf(
                fact("expert_in", "alice", "kubernetes", confidence = 0.9, ids = listOf("old-1", "fresh-1")),
            )
            val repository = InMemoryPropositionRepository().apply {
                save(proposition("old-1", "Alice knows Kubernetes inside out", 0.95, age = yearOld))
                save(proposition("fresh-1", "Alice is a Kubernetes expert", 0.6))
            }

            val derived = only(
                provider(facts).withDeclaration(canHelp).withRepository(repository)
                    .conclusions(RuleGoal.of("can_help('alice', T)"))
            )

            assertEquals(0.6, derived.confidence, 1e-9)
            assertEquals(listOf("old-1", "fresh-1"), derived.inputIds)
            assertEquals(listOf("old-1", "fresh-1"), derived.inputs.map { it.citation.propositionId })
        }

        @Test
        fun `a fact whose sources are not stored keeps its own confidence`() {
            val derived = only(
                provider().withDeclaration(canHelp).withRepository(InMemoryPropositionRepository())
                    .conclusions(RuleGoal.of("can_help('alice', T)"))
            )

            assertEquals(0.9, derived.confidence)
            assertTrue(derived.inputs.isEmpty())
        }

        @Test
        fun `an invalidated source counts at its effective confidence of zero`() {
            val invalidated = Proposition.draft("ctx-main", "Alice is a Kubernetes expert", 0.9)
                .withId("prop-1")
                .withTemporal(TemporalMetadata(invalidatedAt = Instant.now().minus(1, ChronoUnit.DAYS)))
                .toProposition()
            val repository = InMemoryPropositionRepository().apply { save(invalidated) }

            val derived = only(
                provider().withDeclaration(canHelp).withRepository(repository)
                    .conclusions(RuleGoal.of("can_help('alice', T)"))
            )

            assertEquals(0.0, derived.confidence)
            assertEquals(listOf("prop-1"), derived.inputs.map { it.citation.propositionId })
        }
    }
}
