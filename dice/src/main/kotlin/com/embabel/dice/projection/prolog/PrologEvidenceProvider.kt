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

import com.embabel.dice.evidence.DerivationRule
import com.embabel.dice.evidence.Evidence
import com.embabel.dice.evidence.EvidenceConverter
import com.embabel.dice.evidence.EvidenceProvider
import com.embabel.dice.evidence.RuleDeclaration
import com.embabel.dice.evidence.RuleGoal
import com.embabel.dice.evidence.Searched
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStore
import org.jetbrains.annotations.ApiStatus
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Answers a rule goal from projected Prolog facts with derived conclusions, each citing the rule that proved it
 * and the recorded claims it was worked out from.
 *
 * Ask a goal such as `can_help('alice', T)` and every answer Prolog finds becomes an [Evidence.Derived]: the
 * conclusion is the goal with its variables filled in, the rule names the predicate and arity, the goal, each
 * variable's value and a short hash of the loaded theory, and the input ids are the source claims of the facts
 * the proof used. Nothing is stored; recorded claims and derived conclusions stay apart.
 *
 * **Inputs are declared.** The engine doesn't report which facts a proof touched, so each rule clause gets a
 * [RuleDeclaration] naming the facts it reads. For every answer the provider runs the declared clause against
 * that answer, then matches each declared input to the projected facts. Keeping this out of Prolog itself
 * means the same declarations still work if the engine behind this becomes a Datalog one. Declarations are
 * tried in the order they were added; the first that proves the answer and finds a recorded fact with source
 * ids for every input is the one cited. Guards such as `X \= Y` run with the clause but are never cited. An
 * answer no declaration can trace is left out, and so is one whose facts carry no source ids.
 *
 * **Recursive rules are not supported.** A rule that calls itself, such as a chain of managers, can't be
 * declared this way; a proof-tree engine can supply those later. A goal with no declaration for its predicate
 * and arity returns nothing without running a query, but a declared rule that loops would loop here too, because
 * every answer is worked out before the goal's limit is applied. Only ask declared rules that don't recurse.
 *
 * **Answers go back in as real terms.** Each answer's values are written into the goal and the declared clause the
 * way Prolog holds them: numbers bare, compound terms as their term text, and atoms in single quotes exactly as
 * they are, case included. Prolog tells the provider which kind each value is, so an atom that looks like a number,
 * such as `'2024'`, stays an atom. A lone `_` in a declared input or guard gets its own variable name before the
 * clause runs, so the fact it matched is known and only that fact is cited.
 *
 * **Citations need atom arguments.** Facts are written to Prolog through [PrologFact.quoteAtom], which lower-cases
 * and replaces anything outside `a-z`, `0-9` and `_`, so every projected argument is an atom in that form. An input
 * cites a recorded fact only when each of its arguments is an atom spelled exactly as that normalised form: a number
 * such as `2024`, a compound term such as `f(a)`, or an atom such as `'Foo Bar'` that only normalises to `foo_bar`
 * cites nothing, because the proof can't have used the projected fact. Two recorded facts whose arguments only differ
 * in case or punctuation, such as `Alice-Smith` and `alice smith`, are the same projected fact and both are cited.
 *
 * **Confidence** is the weakest input: each matched fact counts at the highest decayed confidence among its source
 * propositions found in the repository (an invalidated one counts at 0.0), or its own confidence when none is found
 * or no repository is set. With a repository the found propositions are also attached as the conclusion's inputs.
 *
 * Two answers with the same filled-in goal give one conclusion, citing the first proof found: declarations in the
 * order added, answers in Prolog's order. A lone `_` in the goal is filled in like any other variable, so answers
 * that differ only there stay separate conclusions. The goal's context is only used to name the search in a
 * [Evidence.Missing]; the projection is taken to hold the facts for that context already.
 *
 * Not provable is not false. A [Evidence.Missing] only says the recorded facts and declared rules don't reach the
 * goal, not that it is untrue.
 *
 * This is experimental, and the engine behind it may change.
 *
 * ```java
 * PrologEvidenceProvider provider = PrologEvidenceProvider.of(projection, schema)
 *     .withDeclaration(RuleDeclaration.of("can_help(X, Topic)").withInput("expert_in(X, Topic)"))
 *     .withRepository(repository);
 * Evidence evidence = provider.evidence(RuleGoal.of("can_help('alice', T)"));
 * ```
 *
 * ```kotlin
 * val provider = PrologEvidenceProvider.of(projection, schema)
 *     .withDeclaration(RuleDeclaration.of("can_help(X, Topic)").withInput("expert_in(X, Topic)"))
 *     .withRepository(repository)
 * val evidence = provider.evidence(RuleGoal.of("can_help('alice', T)"))
 * ```
 */
@ApiStatus.Experimental
class PrologEvidenceProvider private constructor(
    private val projection: PrologProjectionResult,
    private val schema: PrologSchema,
    declarations: List<RuleDeclaration>,
    private val repository: PropositionStore?,
) : EvidenceProvider<RuleGoal> {

    private val declarations: List<RuleDeclaration> = declarations.toList()

    // The engine and the hash are built from one string, so the hash always names what the engine loaded.
    private val theory: String = projection.toTheory(schema)
    private val theoryHash: String = sha256Hex(theory).take(16)
    private val engine: PrologEngine = PrologEngine.fromTheory(theory)

    // Facts that can be cited, keyed by predicate and each argument's atom text as the projection wrote it to
    // Prolog. Facts with no usable ids are left out.
    private val citableFacts: Map<FactKey, List<CitableFact>> = projection.facts
        .mapNotNull { fact ->
            val ids = fact.sourcePropositionIds.filter { it.isNotBlank() }
            if (ids.isEmpty()) null else CitableFact(fact, ids)
        }
        .groupBy { citable ->
            FactKey(citable.fact.predicate, citable.fact.args.map { PrologFact.quoteAtom(it).removeSurrounding("'") })
        }

    /**
     * Returns a copy that also traces answers through [declaration], tried after the declarations already there.
     *
     * @param declaration one clause of a rule and the facts it reads
     * @return the updated provider
     */
    fun withDeclaration(declaration: RuleDeclaration): PrologEvidenceProvider =
        PrologEvidenceProvider(projection, schema, declarations + declaration, repository)

    /**
     * Returns a copy that looks up each cited fact's source propositions in [repository], for their decayed
     * confidence and to attach them as the conclusion's inputs.
     *
     * @param repository where the source propositions are stored
     * @return the updated provider
     */
    fun withRepository(repository: PropositionStore): PrologEvidenceProvider =
        PrologEvidenceProvider(projection, schema, declarations, repository)

    /**
     * Returns the first conclusion for [query], or a [Evidence.Missing] naming the goal, and its context when set,
     * when nothing declared can be proved and traced. Not provable is not false.
     *
     * @param query the goal to ask
     * @return the first [Evidence.Derived], or a [Evidence.Missing]
     */
    override fun evidence(query: RuleGoal): Evidence =
        conclusions(query).firstOrNull() ?: Evidence.missing(searchedFor(query))

    /**
     * Returns every conclusion for [goal], at most [RuleGoal.limit] of them, in the order Prolog finds the answers.
     * Empty when the goal has no declaration or nothing declared can be proved and traced.
     *
     * @param goal the goal to ask
     * @return the conclusions, one per distinct filled-in goal
     */
    fun conclusions(goal: RuleGoal): List<Evidence.Derived> {
        val declared = declarations.filter { it.predicate == goal.predicate && it.arity == goal.arity }
        if (declared.isEmpty()) return emptyList()

        val goalVariables = variablesIn(goal.goal)
        // Each lone _ in the goal gets a fresh name too, so its value comes back and is filled in. Left as _, two
        // answers that differ only there would look like the same goal and one of them would be dropped.
        val anonymous = freshNames("Anon_", goalVariables.toSet()).iterator()
        val namedGoal = rewriteVariables(goal.goal) { if (it == "_") anonymous.next() else null }
        val namedVariables = variablesIn(namedGoal)
        // The engine hands back every value as text, so the goal also asks Prolog what kind each value is.
        val kindOf = namedVariables.asSequence().zip(freshNames("Kind_", namedVariables.toSet())).toMap()
        val probedGoal = (listOf("($namedGoal)") + kindOf.map { (name, kind) -> kindProbe(name, kind) })
            .joinToString(", ")

        val seen = HashSet<String>()
        val conclusions = ArrayList<Evidence.Derived>()
        for (answer in engine.queryAll(probedGoal)) {
            if (conclusions.size >= goal.limit) break
            if (!answer.success) continue
            val groundGoal = rewriteVariables(namedGoal) { name ->
                kindOf[name]?.let { kind -> answer.bindings[name]?.let { termText(it, answer.bindings[kind]) } }
            }
            if (!seen.add(groundGoal)) continue
            val facts = declared.firstNotNullOfOrNull { trace(it, groundGoal) } ?: continue
            conclusions += derive(goal, groundGoal, goalVariables, answer.bindings, facts)
        }
        return conclusions
    }

    // Runs the declared clause against one ground answer and returns the recorded facts behind it, or null when
    // the clause doesn't prove it or some input has no citable fact.
    private fun trace(declaration: RuleDeclaration, groundGoal: String): List<CitableFact>? {
        // Each lone _ gets a fresh name, unused anywhere in the query, so its value comes back and the exact fact
        // can be cited instead of the anonymous variable matching nothing.
        val taken = (listOf(declaration.head, groundGoal) + declaration.inputs + declaration.guards)
            .flatMap(::variablesIn).toSet()
        val fresh = freshNames("Anon_", taken).iterator()
        val name: (String) -> String = { text -> rewriteVariables(text) { if (it == "_") fresh.next() else null } }
        val inputs = declaration.inputs.map(name)
        val guards = declaration.guards.map(name)

        // Recorded facts only hold atoms, so the query also asks what kind of value each input variable ends up
        // with. A number or compound there means the proof used something other than a projected fact.
        val named = (listOf(declaration.head, groundGoal) + inputs + guards).flatMap(::variablesIn).toSet()
        val inputVariables = inputs.flatMap(::variablesIn).distinct()
        val kindOf = inputVariables.asSequence().zip(freshNames("Kind_", named)).toMap()

        val query = buildList {
            add("${declaration.head} = ($groundGoal)")
            addAll(inputs)
            // Bracketed so a guard using ; or -> can't swallow the goals around it.
            guards.forEach { add("($it)") }
            kindOf.forEach { (variable, kind) -> add(kindProbe(variable, kind)) }
        }.joinToString(", ")

        for (proof in engine.queryAll(query)) {
            if (!proof.success) continue
            val perInput = inputs.map { input ->
                val atomsOnly = variablesIn(input).all { proof.bindings[kindOf.getValue(it)] == "a" }
                if (!atomsOnly) emptyList()
                else citableFactsFor(rewriteVariables(input) { proof.bindings[it]?.let(::quotedAtom) })
            }
            if (perInput.any { it.isEmpty() }) continue
            val facts = perInput.flatten()
            if (facts.isNotEmpty()) return facts
        }
        return null
    }

    private fun citableFactsFor(groundInput: String): List<CitableFact> {
        val open = groundInput.indexOf('(')
        if (open < 0 || !groundInput.endsWith(")")) return emptyList()
        val predicate = groundInput.substring(0, open).trim()
        val args = splitArguments(groundInput.substring(open + 1, groundInput.length - 1)).map(::atomText)
        if (args.any { it == null }) return emptyList()
        return citableFacts[FactKey(predicate, args.filterNotNull())].orEmpty()
    }

    private fun derive(
        goal: RuleGoal,
        groundGoal: String,
        goalVariables: List<String>,
        bindings: Map<String, String>,
        facts: List<CitableFact>,
    ): Evidence.Derived {
        val inputIds = facts.flatMap { it.ids }.distinct()
        val now = Instant.now()
        val found: Map<String, Proposition> = repository?.let { store ->
            inputIds.mapNotNull { id -> store.findById(id)?.takeIf { it.id == id }?.let { id to it } }.toMap()
        }.orEmpty()

        val confidence = facts.minOf { fact ->
            fact.ids.mapNotNull { found[it]?.effectiveConfidenceAt(now) }.maxOrNull() ?: fact.fact.confidence
        }

        var rule = DerivationRule.of("prolog")
            .withRuleId("${goal.predicate}/${goal.arity}")
            .withExpression(goal.goal)
        goalVariables.forEach { name -> bindings[name]?.let { rule = rule.withParameter("binding.$name", it) } }
        rule = rule.withParameter("theory", theoryHash)

        // Items worked out at the same instant as the confidence above, so the two agree.
        val converter = EvidenceConverter.create().withClock(Clock.fixed(now, ZoneOffset.UTC))
        val inputs = inputIds.mapNotNull { found[it] }.filter { it.text.isNotBlank() }.map(converter::item)
        return Evidence.derived(groundGoal, confidence, rule, inputIds).withInputs(inputs)
    }

    private fun searchedFor(goal: RuleGoal): Searched =
        (goal.contextId?.let { Searched.of(it) } ?: Searched.unscoped()).withQuery(goal.goal)

    private data class FactKey(val predicate: String, val args: List<String>)

    private class CitableFact(val fact: PrologFact, val ids: List<String>)

    companion object {

        /**
         * Creates a provider over [projection] with the rules in [schema], with no declarations and no repository.
         *
         * @param projection the projected facts to reason over
         * @param schema the schema whose base rules are loaded with the facts
         * @return the provider
         */
        @JvmStatic
        fun of(projection: PrologProjectionResult, schema: PrologSchema): PrologEvidenceProvider =
            PrologEvidenceProvider(projection, schema, emptyList(), null)

        private val QUOTES = setOf('\'', '"', '`')

        private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        // Rewrites every variable in a Prolog term with the value [replace] gives it, leaving quoted text alone.
        // A variable is a name starting with an upper-case letter or `_`, the anonymous `_` included.
        private fun rewriteVariables(text: String, replace: (String) -> String?): String = buildString {
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quote != null) {
                    append(c)
                    when {
                        c == '\\' && i + 1 < text.length -> append(text[++i])
                        c == quote && text.getOrNull(i + 1) == quote -> append(text[++i])
                        c == quote -> quote = null
                    }
                    i++
                } else if (c in QUOTES) {
                    quote = c
                    append(c)
                    i++
                } else if (isNameChar(c)) {
                    var end = i
                    while (end < text.length && isNameChar(text[end])) end++
                    val name = text.substring(i, end)
                    val isVariable = name[0].isUpperCase() || name[0] == '_'
                    append(if (isVariable) replace(name) ?: name else name)
                    i = end
                } else {
                    append(c)
                    i++
                }
            }
        }

        // Asks Prolog what kind of value a variable holds: n for a number, a for an atom, anything else for the rest.
        private fun kindProbe(variable: String, kind: String): String =
            "(number($variable) -> $kind = n ; atom($variable) -> $kind = a ; $kind = t)"

        // Writes one answer value back as Prolog reads it. Numbers and compound terms come from the engine as
        // valid term text already; an atom comes back bare, so it's quoted again, or when its kind is unknown.
        private fun termText(value: String, kind: String?): String =
            if (kind == "n" || kind == "t") value else quotedAtom(value)

        // Quotes an atom exactly as it is. The backslash and the quote are escaped, and so are line feeds, carriage
        // returns and tabs, which would otherwise break the query the atom is written into.
        private fun quotedAtom(value: String): String =
            "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'"

        // Names not in [taken], such as Anon_1, Anon_2 and so on.
        private fun freshNames(prefix: String, taken: Set<String>): Sequence<String> =
            generateSequence(1) { it + 1 }.map { "$prefix$it" }.filter { it !in taken }

        // The named variables in a term, in order of first appearance; the anonymous `_` doesn't count.
        private fun variablesIn(text: String): List<String> {
            val names = LinkedHashSet<String>()
            rewriteVariables(text) { name -> if (name != "_") names += name; null }
            return names.toList()
        }

        // Splits a term's argument text on the commas at the top level, outside quotes and nested brackets.
        private fun splitArguments(text: String): List<String> {
            val args = ArrayList<String>()
            val current = StringBuilder()
            var depth = 0
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quote != null) {
                    current.append(c)
                    when {
                        c == '\\' && i + 1 < text.length -> current.append(text[++i])
                        c == quote && text.getOrNull(i + 1) == quote -> current.append(text[++i])
                        c == quote -> quote = null
                    }
                } else when (c) {
                    in QUOTES -> { quote = c; current.append(c) }
                    '(', '[', '{' -> { depth++; current.append(c) }
                    ')', ']', '}' -> { depth--; current.append(c) }
                    ',' -> if (depth == 0) { args += current.toString(); current.clear() } else current.append(c)
                    else -> current.append(c)
                }
                i++
            }
            args += current.toString()
            return args.map { it.trim() }
        }

        private val BARE_ATOM = Regex("[a-z][a-zA-Z0-9_]*")

        // The text of an argument that is an atom, either quoted or bare, or null for a number, a compound term or
        // anything else, which can never be a projected fact's argument.
        private fun atomText(arg: String): String? {
            if (BARE_ATOM.matches(arg)) return arg
            if (arg.length < 2 || !arg.startsWith('\'') || !arg.endsWith('\'')) return null
            val inner = arg.substring(1, arg.length - 1)
            val bare = buildString {
                var i = 0
                while (i < inner.length) {
                    val c = inner[i]
                    when {
                        c == '\\' && i + 1 < inner.length -> { append(inner[i + 1]); i += 2 }
                        c == '\'' && inner.getOrNull(i + 1) == '\'' -> { append(c); i += 2 }
                        // A lone quote closes the atom early, so this is a term such as 'f'('a'), not one atom.
                        c == '\'' -> return null
                        else -> { append(c); i++ }
                    }
                }
            }
            return bare
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
