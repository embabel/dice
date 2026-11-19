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
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
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
    inner class BindingTerms {

        @Test
        fun `a number in the rule head is written back unquoted and the rule still traces`() {
            // Projected fact args are always atoms, so the number comes from the confidence metadata the
            // projection writes next to each fact, here confidence(expert_in('alice', 'kubernetes'), 0.9).
            val rules = "rated(X, C) :- expert_in(X, T), confidence(expert_in(X, T), C), C > 0.5."
            val rated = RuleDeclaration.of("rated(X, C)")
                .withInput("expert_in(X, T)")
                .withGuard("confidence(expert_in(X, T), C), C > 0.5")

            val derived = only(provider(rules = rules).withDeclaration(rated).conclusions(RuleGoal.of("rated('alice', C)")))

            assertEquals("rated('alice', 0.9)", derived.conclusion)
            assertEquals("0.9", derived.rule.parameters["binding.C"])
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `an atom that looks like a number stays a quoted atom`() {
            val facts = listOf(fact("founded_in", "acme", "2024", confidence = 0.9, ids = listOf("prop-y")))
            val founded = RuleDeclaration.of("founded(C, Y)").withInput("founded_in(C, Y)")

            val derived = only(
                provider(facts, "founded(C, Y) :- founded_in(C, Y).")
                    .withDeclaration(founded)
                    .conclusions(RuleGoal.of("founded('acme', Y)"))
            )

            assertEquals("founded('acme', '2024')", derived.conclusion)
            assertEquals(listOf("prop-y"), derived.inputIds)
        }

        @Test
        fun `a compound term in the rule head is written back as its term text and the rule still traces`() {
            val rules = "pair_of(X, pair(X, T)) :- expert_in(X, T)."
            val pairOf = RuleDeclaration.of("pair_of(X, pair(X, T))").withInput("expert_in(X, T)")

            val derived = only(provider(rules = rules).withDeclaration(pairOf).conclusions(RuleGoal.of("pair_of('alice', P)")))

            assertEquals("pair_of('alice', pair(alice, kubernetes))", derived.conclusion)
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a mixed-case atom binding round-trips into the conclusion unchanged, quotes escaped`() {
            val rules = "title(X, 'Alice''s Mentor') :- expert_in(X, _)."
            val title = RuleDeclaration.of("title(X, 'Alice''s Mentor')").withInput("expert_in(X, T)")

            val derived = only(provider(rules = rules).withDeclaration(title).conclusions(RuleGoal.of("title('alice', L)")))

            assertEquals("title('alice', 'Alice\\'s Mentor')", derived.conclusion)
            assertEquals("Alice's Mentor", derived.rule.parameters["binding.L"])
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `an anonymous variable in a declared input cites the exact fact and only that fact`() {
            val facts = listOf(
                fact("works_at", "alice", "acme", confidence = 0.9, ids = listOf("prop-a")),
                fact("works_at", "bob", "acme", confidence = 0.8, ids = listOf("prop-b")),
                fact("works_at", "carol", "globex", confidence = 0.7, ids = listOf("prop-c")),
            )
            val employed = RuleDeclaration.of("employed(X)").withInput("works_at(X, _)")

            val derived = only(
                provider(facts, "employed(X) :- works_at(X, _).")
                    .withDeclaration(employed)
                    .conclusions(RuleGoal.of("employed('alice')"))
            )

            assertEquals("employed('alice')", derived.conclusion)
            assertEquals(listOf("prop-a"), derived.inputIds)
            assertEquals(0.9, derived.confidence)
        }

        @Test
        fun `the fresh name for an anonymous variable never clashes with the declaration's own variables`() {
            // If the anonymous variable became Anon_1 it would be forced equal to the person, and nothing would match.
            val facts = listOf(fact("works_at", "alice", "acme", confidence = 0.9, ids = listOf("prop-a")))
            val employed = RuleDeclaration.of("employed(Anon_1)").withInput("works_at(Anon_1, _)")

            val derived = only(
                provider(facts, "employed(X) :- works_at(X, _).")
                    .withDeclaration(employed)
                    .conclusions(RuleGoal.of("employed(P)"))
            )

            assertEquals("employed('alice')", derived.conclusion)
            assertEquals(listOf("prop-a"), derived.inputIds)
        }

        @Test
        fun `an anonymous variable in the goal is filled in, so different answers stay separate conclusions`() {
            val facts = listOf(
                fact("expert_in", "alice", "kotlin", confidence = 0.9, ids = listOf("prop-a")),
                fact("expert_in", "bob", "kotlin", confidence = 0.8, ids = listOf("prop-b")),
            )

            val conclusions = provider(facts, canHelpRule).withDeclaration(canHelp).conclusions(RuleGoal.of("can_help(_, T)"))

            assertEquals(
                listOf("can_help('alice', 'kotlin')", "can_help('bob', 'kotlin')"),
                conclusions.map { it.conclusion },
            )
            assertEquals(listOf(listOf("prop-a"), listOf("prop-b")), conclusions.map { it.inputIds })
            assertEquals("can_help(_, T)", conclusions.first().rule.expression)
        }

        @Test
        fun `the fresh name for an anonymous variable in the goal never clashes with the goal's own variables`() {
            // If the anonymous variable became Anon_1 it would be forced equal to the topic, and nothing would match.
            val facts = listOf(fact("expert_in", "alice", "kotlin", confidence = 0.9, ids = listOf("prop-a")))

            val derived = only(
                provider(facts, canHelpRule).withDeclaration(canHelp).conclusions(RuleGoal.of("can_help(_, Anon_1)"))
            )

            assertEquals("can_help('alice', 'kotlin')", derived.conclusion)
            assertEquals(listOf("prop-a"), derived.inputIds)
        }

        @Test
        fun `an atom holding a line break is escaped, so its answer is still traced`() {
            val rules = "label(X, 'a\\nb') :- expert_in(X, _)."
            val label = RuleDeclaration.of("label(X, L)").withInput("expert_in(X, T)")

            val derived = only(provider(rules = rules).withDeclaration(label).conclusions(RuleGoal.of("label('alice', L)")))

            assertEquals("label('alice', 'a\\nb')", derived.conclusion)
            assertEquals("a\nb", derived.rule.parameters["binding.L"])
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `an atom holding a carriage return or tab is escaped too`() {
            val rules = "label(X, 'a\\rb\\tc') :- expert_in(X, _)."
            val label = RuleDeclaration.of("label(X, L)").withInput("expert_in(X, T)")

            val derived = only(provider(rules = rules).withDeclaration(label).conclusions(RuleGoal.of("label('alice', L)")))

            assertEquals("label('alice', 'a\\rb\\tc')", derived.conclusion)
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a small real answer is written back so it reads again, and the conclusion is given`() {
            // The engine shows 0.0000001 as 1E-7, which it can't read back without a decimal point.
            val rules = "tiny(X, 0.0000001) :- expert_in(X, _)."
            val tiny = RuleDeclaration.of("tiny(X, V)").withInput("expert_in(X, T)")

            val derived = only(provider(rules = rules).withDeclaration(tiny).conclusions(RuleGoal.of("tiny('alice', V)")))

            assertEquals("tiny('alice', 1.0e-7)", derived.conclusion)
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a small real worked out by an arithmetic guard gives its conclusion`() {
            val rules = "scaled(X, S) :- expert_in(X, T), confidence(expert_in(X, T), C), S is C / 10000000."
            val scaled = RuleDeclaration.of("scaled(X, S)")
                .withInput("expert_in(X, T)")
                .withGuard("confidence(expert_in(X, T), C), S is C / 10000000")
            val provider = provider(rules = rules).withDeclaration(scaled)
            val goal = RuleGoal.of("scaled('alice', S)")

            val derived = assertInstanceOf(Evidence.Derived::class.java, provider.evidence(goal))
            assertTrue(PrologEngine.fromTheory("").query("(${derived.conclusion}) = scaled(_, S), S =:= 0.00000009"))
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a negative number written after an operator in the goal still reads as one term`() {
            // Written straight after the minus, -5 would make --, which Prolog reads as one unknown operator.
            val rules = "gap(alice-(-5)) :- expert_in(alice, _)."
            val gap = RuleDeclaration.of("gap(X-D)").withInput("expert_in(X, T)")

            val derived = only(provider(rules = rules).withDeclaration(gap).conclusions(RuleGoal.of("gap('alice'-D)")))

            assertTrue(PrologEngine.fromTheory("").query("(${derived.conclusion}) == gap(alice-(-5))"))
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a compound written next to an operator in the goal keeps its own shape`() {
            // Unbracketed, a :- b - 1 would read as a :- (b - 1), a different term that the rule head doesn't match.
            val rules = "op_pair((a :- b) - 1) :- expert_in(alice, _)."
            val opPair = RuleDeclaration.of("op_pair(X - N)").withInput("expert_in(alice, T)")

            val derived = only(provider(rules = rules).withDeclaration(opPair).conclusions(RuleGoal.of("op_pair(X - 1)")))

            assertTrue(PrologEngine.fromTheory("").query("(${derived.conclusion}) == op_pair((a :- b) - 1)"))
            assertEquals(listOf("prop-1"), derived.inputIds)
        }
    }

    @Nested
    inner class RoundTripTerms {

        private val holds = RuleDeclaration.of("holds(X, V)").withInput("expert_in(X, T)")

        // Each value as Prolog source; the engine binds it, the provider writes it back, and the written text
        // must read as the very same term.
        private val table = listOf(
            "atom with a line feed" to "'a\\nb'",
            "atom with a carriage return" to "'a\\rb'",
            "atom with a tab" to "'a\\tb'",
            "atom with a backslash" to "'a\\\\b'",
            "atom with a single quote" to "'it''s'",
            "atom with another control character" to "'a\\x7\\b'",
            "atom with a NUL" to "'a\\x0\\b'",
            "atom with a non-ASCII letter" to "'café'",
            "empty atom" to "''",
            "mixed-case atom" to "'Alice Smith'",
            "integer" to "42",
            "zero" to "0",
            "negative integer" to "-5",
            "integer beyond Long" to "123456789012345678901234567890",
            "negative integer beyond Long" to "-123456789012345678901234567890",
            "real 1E-7" to "0.0000001",
            "real 1.0E-7" to "1.0e-7",
            "real 1.5E10" to "1.5e10",
            "real 1E10" to "1.0e10",
            "negative real" to "-2.5",
            "negative small real" to "-0.0000001",
            "whole real" to "2.0",
            "plain real" to "0.9",
            "huge real" to "1.5e300",
            "compound holding a small real" to "f(0.0000001, -5, 'a\\nb', 'it''s')",
            "compound holding a control character and a small real" to "f('a\\x7\\', 0.0000001)",
            "list holding small reals" to "[0.0000001, -0.0000001, 1.0e10]",
            "operator compound with a negative" to "1 - -5",
        )

        @Test
        fun `every bound value is written back as text that reads as the same term`() {
            assertAll(table.map { (name, source) ->
                Executable {
                    val rules = "holds(X, V) :- expert_in(X, _), V = ($source)."
                    val conclusions = provider(rules = rules).withDeclaration(holds)
                        .conclusions(RuleGoal.of("holds('alice', V)"))

                    assertEquals(1, conclusions.size, "$name: expected one conclusion")
                    val derived = conclusions.single()
                    assertEquals(listOf("prop-1"), derived.inputIds, "$name: cited ids")
                    assertTrue(
                        PrologEngine.fromTheory("").query("(${derived.conclusion}) = holds(_, W), W == ($source)"),
                        "$name: ${derived.conclusion} should read back as holds('alice', $source)",
                    )
                }
            })
        }
    }

    @Nested
    inner class StructuralTerms {

        // In a rule, -N with N unbound is the compound -(N), so this proves negated(alice, -(5)), never -5.
        private val negatedRule = "negated(X, -N) :- expert_in(X, _), N = 5."

        private fun provable(conclusion: String, rules: String) =
            PrologEngine.fromProjection(projection(baseFacts), PrologSchema.withRules(rules)).query(conclusion)

        @Test
        fun `a number filled in under a prefix minus keeps the compound the rule proved`() {
            val exact = RuleDeclaration.of("negated(X, -N)").withInput("expert_in(X, T)").withGuard("N = 5")

            val derived = only(
                provider(rules = negatedRule).withDeclaration(exact).conclusions(RuleGoal.of("negated('alice',-N)"))
            )

            assertTrue(PrologEngine.fromTheory("").query("(${derived.conclusion}) == negated(alice, -(5))"))
            assertTrue(provable(derived.conclusion, negatedRule), "${derived.conclusion} should be provable")
            assertEquals(listOf("prop-1"), derived.inputIds)
        }

        @Test
        fun `a generic declaration never gives a conclusion the rules can't prove`() {
            val generic = RuleDeclaration.of("negated(X, Y)").withInput("expert_in(X, T)")

            val conclusions = provider(rules = negatedRule).withDeclaration(generic)
                .conclusions(RuleGoal.of("negated('alice',-N)"))

            assertEquals(1, conclusions.size)
            conclusions.forEach {
                assertTrue(provable(it.conclusion, negatedRule), "${it.conclusion} should be provable")
            }
        }

        @Test
        fun `an input argument written in brackets cites the projected fact`() {
            val rules = "knows_kotlin(X) :- expert_in(X, kotlin)."
            val knows = RuleDeclaration.of("knows_kotlin(X)").withInput("expert_in(X, (kotlin))")

            val derived = only(provider(rules = rules).withDeclaration(knows).conclusions(RuleGoal.of("knows_kotlin(X)")))

            assertEquals("knows_kotlin('bob')", derived.conclusion)
            assertEquals(listOf("prop-4"), derived.inputIds)
        }

        @Test
        fun `an input argument in double quotes is read as the atom, so it cites the projected fact`() {
            // The engine's parser reads "kotlin" as the atom kotlin, not a string or a code list.
            val rules = "knows_kotlin(X) :- expert_in(X, kotlin)."
            val knows = RuleDeclaration.of("knows_kotlin(X)").withInput("expert_in(X, \"kotlin\")")

            val derived = only(provider(rules = rules).withDeclaration(knows).conclusions(RuleGoal.of("knows_kotlin(X)")))

            assertEquals("knows_kotlin('bob')", derived.conclusion)
            assertEquals(listOf("prop-4"), derived.inputIds)
        }

        @Test
        fun `a goal holding a character code selects the declaration with its real arity`() {
            // 0'A is the character code 65; its apostrophe doesn't open a quoted atom.
            val rules = "help(P, C, T) :- expert_in(P, T), integer(C)."
            val help = RuleDeclaration.of("help(P, C, T)").withInput("expert_in(P, T)")

            val derived = only(
                provider(rules = rules).withDeclaration(help).conclusions(RuleGoal.of("help('alice', 0'A, T)"))
            )

            assertTrue(PrologEngine.fromTheory("").query("(${derived.conclusion}) == help(alice, 65, kubernetes)"))
            assertEquals("help/3", derived.rule.ruleId)
            assertEquals(listOf("prop-1"), derived.inputIds)
        }
    }

    @Nested
    inner class AtomOnlyCitations {

        // tagged/1 reads value/1, which the schema states for itself next to the projected value facts.
        private val tagged = RuleDeclaration.of("tagged(X)").withInput("value(X)")

        private fun taggedProvider(projected: String, schemaFact: String) = provider(
            listOf(fact("value", projected, confidence = 0.9, ids = listOf("prop-v"))),
            "$schemaFact\ntagged(X) :- value(X).",
        ).withDeclaration(tagged)

        @Test
        fun `a number binding never cites the atom fact it would quote to`() {
            val provider = taggedProvider("2024", "value(2024).")
            val goal = RuleGoal.of("tagged(2024)")

            assertTrue(provider.conclusions(goal).isEmpty())
            assertInstanceOf(Evidence.Missing::class.java, provider.evidence(goal))
        }

        @Test
        fun `a compound binding never cites an atom fact`() {
            val provider = taggedProvider("f(a)", "value(f(a)).")

            assertTrue(provider.conclusions(RuleGoal.of("tagged(f(a))")).isEmpty())
        }

        @Test
        fun `a mixed-case or spaced atom binding never cites a fact it only normalises to`() {
            val provider = taggedProvider("Foo Bar", "value('Foo Bar').")

            assertTrue(provider.conclusions(RuleGoal.of("tagged('Foo Bar')")).isEmpty())
        }

        @Test
        fun `an atom binding equal to the projected fact's normalised argument cites it`() {
            val provider = taggedProvider("Foo Bar", "value('Foo Bar').")

            val derived = only(provider.conclusions(RuleGoal.of("tagged(foo_bar)")))
            assertEquals("tagged(foo_bar)", derived.conclusion)
            assertEquals(listOf("prop-v"), derived.inputIds)
        }

        @Test
        fun `an atom holding an escape never cites the projected atom its escape letter spells`() {
            // 'a\nb' holds a line break; dropping the backslash would read it as anb, the projected atom.
            val provider = taggedProvider("anb", "value('a\\nb').")
            val goal = RuleGoal.of("tagged('a\\nb')")

            assertTrue(provider.conclusions(goal).isEmpty())
            assertInstanceOf(Evidence.Missing::class.java, provider.evidence(goal))
        }

        @Test
        fun `only the plain projected atom cites when an escaped atom also answers`() {
            val provider = taggedProvider("anb", "value('a\\nb').")

            val derived = only(provider.conclusions(RuleGoal.of("tagged(X)")))
            assertEquals("tagged('anb')", derived.conclusion)
            assertEquals(listOf("prop-v"), derived.inputIds)

            assertEquals(listOf("prop-v"), only(provider.conclusions(RuleGoal.of("tagged(anb)"))).inputIds)
        }

        @Test
        fun `a declared input spelling an atom with an escape other than backslash or quote cites nothing`() {
            // '\x61\b' reads as the atom ab, but escapes other than \\ and \' are never read for a citation, so this
            // declaration finds no fact even though the proof used value(ab).
            val rules = "has_a(X) :- expert_in(X, _), value(ab)."
            val facts = baseFacts + fact("value", "ab", confidence = 0.9, ids = listOf("prop-v"))
            val escaped = RuleDeclaration.of("has_a(X)").withInput("expert_in(X, T)").withInput("value('\\x61\\b')")
            val plain = RuleDeclaration.of("has_a(X)").withInput("expert_in(X, T)").withInput("value(ab)")

            assertTrue(provider(facts, rules).withDeclaration(escaped).conclusions(RuleGoal.of("has_a('alice')")).isEmpty())
            assertEquals(
                listOf("prop-1", "prop-v"),
                only(provider(facts, rules).withDeclaration(plain).conclusions(RuleGoal.of("has_a('alice')"))).inputIds,
            )
        }

        @Test
        fun `an escaped goal argument cites the fact Prolog reads it as, because the proof used that atom`() {
            // Prolog itself reads '\x61\' in the goal as a, so the input is value(a), exactly the projected atom.
            val provider = taggedProvider("a", "value(a).")

            assertEquals(listOf("prop-v"), only(provider.conclusions(RuleGoal.of("tagged('\\x61\\')"))).inputIds)
        }

        @Test
        fun `a literal number or compound in a declared input matches no fact`() {
            val rules = "value(2024).\nvalue(f(a)).\nflag(X) :- expert_in(X, _), value(2024).\n" +
                "mark(X) :- expert_in(X, _), value(f(a))."
            val facts = baseFacts + listOf(
                fact("value", "2024", confidence = 0.9, ids = listOf("prop-n")),
                fact("value", "f(a)", confidence = 0.9, ids = listOf("prop-c")),
            )
            val flag = RuleDeclaration.of("flag(X)").withInput("expert_in(X, T)").withInput("value(2024)")
            val mark = RuleDeclaration.of("mark(X)").withInput("expert_in(X, T)").withInput("value(f(a))")
            val provider = provider(facts, rules).withDeclaration(flag).withDeclaration(mark)

            assertTrue(provider.conclusions(RuleGoal.of("flag('alice')")).isEmpty())
            assertTrue(provider.conclusions(RuleGoal.of("mark('alice')")).isEmpty())
        }

        @Test
        fun `a literal atom in a declared input cites the projected fact it names`() {
            val rules = "knows_k8s(X) :- expert_in(X, kubernetes)."
            val knows = RuleDeclaration.of("knows_k8s(X)").withInput("expert_in(X, kubernetes)")

            val derived = only(provider(rules = rules).withDeclaration(knows).conclusions(RuleGoal.of("knows_k8s(X)")))
            assertEquals("knows_k8s('alice')", derived.conclusion)
            assertEquals(listOf("prop-1"), derived.inputIds)
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
