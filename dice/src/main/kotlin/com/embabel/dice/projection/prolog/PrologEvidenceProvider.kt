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
import com.embabel.dice.evidence.characterCodeEnd
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStore
import it.unibo.tuprolog.core.Atom
import it.unibo.tuprolog.core.Cons
import it.unibo.tuprolog.core.EmptyList
import it.unibo.tuprolog.core.Integer
import it.unibo.tuprolog.core.Real
import it.unibo.tuprolog.core.Struct
import it.unibo.tuprolog.core.Substitution
import it.unibo.tuprolog.core.Term
import it.unibo.tuprolog.core.Tuple
import it.unibo.tuprolog.core.Var
import it.unibo.tuprolog.core.parsing.parse
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.math.BigDecimal
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
 * [RuleDeclaration] naming the facts it reads. For every answer the provider replays the declared clause with
 * the goal's original bindings and matches each declared input to the projected facts once the replay reaches
 * that answer. Keeping this out of Prolog itself means the same declarations still work if the engine behind
 * this becomes a Datalog one. Declarations are tried in the order they were added. The first to prove the
 * answer and find a recorded fact with source ids for every input is the one cited. Guards such as `X \= Y`
 * run alongside the inputs in the order they were added. Only the inputs are cited. The provider leaves
 * out answers it can't trace to recorded facts with source ids. If an answer leaves variables free, its
 * proof must preserve those variables and which arguments share them. Each answer runs its own replay, so a goal
 * with unbound variables can visit the same inputs many times.
 *
 * **Recursive rules are not supported.** A rule that calls itself, such as a chain of managers, can't be
 * declared this way; a proof-tree engine can supply those later. A goal with no declaration for its predicate
 * and arity returns nothing without running a query, but a declared rule that loops would loop here too, because
 * every answer is worked out before the goal's limit is applied. Only ask declared rules that don't recurse.
 *
 * **Answers stay terms.** The goal and each declaration's head, inputs and guards are parsed into Prolog terms once,
 * and tracing, citing and spotting repeats all use Prolog terms, so a value always means exactly what Prolog
 * bound. Declarations are picked by the parsed goal's name and arity. A lone
 * `_`, in the goal or in a declared input or guard, gets a variable of its own so its value comes back: answers that
 * differ only there stay separate conclusions, and an input with a `_` cites only the fact it matched.
 *
 * **Conclusion text reads back as the proved term.** The conclusion is the goal as written with each variable's
 * value filled in: atoms in single quotes exactly as they are, case included, integers bare, a real always with a
 * digit on both sides of the decimal point (such as `1.0e-7`), and compound terms written by name, such as
 * `pair(alice, kubernetes)` or `'-'(5)`. The provider reads that text back and keeps it only when Prolog reads it as
 * the very term the answer proved. When it doesn't, each filled-in value is written again in brackets and checked
 * once more: `negated('alice', -N)` with `N` as 5 would read as the number -5, so it becomes `negated('alice', -(5))`.
 * An answer whose text still reads as some other term is left out, with a warning naming the goal, so a conclusion's
 * text never names a term other than the one proved.
 *
 * **Citations need atom arguments.** Facts are written to Prolog through [PrologFact.quoteAtom], which lower-cases
 * and replaces anything outside `a-z`, `0-9` and `_`, so every projected argument is an atom in that form. An input
 * cites a recorded fact only when each of its arguments, as Prolog parsed and proved it, is an atom equal to that
 * normalised form. How a declaration spells the atom doesn't matter: `kotlin`, `'kotlin'`, `(kotlin)` and
 * `"kotlin"` (which this engine reads as an atom) all cite the same fact. A number such as `2024`, a compound term
 * such as `f(a)`, or an atom such as `'Foo Bar'` that only normalises to `foo_bar` cites nothing, because the proof
 * can't have used the projected fact. Two recorded facts whose arguments only differ in case or punctuation, such as
 * `Alice-Smith` and `alice smith`, are the same projected fact and both are cited. Escapes are read as Prolog reads
 * them, so `value('\x61\b')` is the atom `ab` and cites the projected `value(ab)`, while `value('a\nb')` holds a line
 * break and doesn't cite `value(anb)`.
 *
 * **Confidence** is the weakest input: each matched fact counts at the highest decayed confidence among its source
 * propositions found in the repository (an invalidated one counts at 0.0), or its own confidence when none is found
 * or no repository is set. With a repository the found propositions are also attached as the conclusion's inputs.
 *
 * Two answers whose filled-in goals are the same term give one conclusion, citing the first proof found: declarations
 * in the order added, answers in Prolog's order. The goal's context is only used to name the search in a
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

    // Each declaration parsed once, in the order added. One that Prolog can't parse can never trace anything.
    private val parsedDeclarations: List<ParsedDeclaration> = this.declarations.mapNotNull(::parseDeclaration)

    // Facts that can be cited, keyed by predicate and each argument's atom as the projection wrote it to Prolog.
    // Facts with no usable ids are left out.
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
        val parsed = parseTerm(goal.goal) as? Struct ?: return emptyList()
        val declared = parsedDeclarations.filter { it.head.functor == parsed.functor && it.head.arity == parsed.arity }
        if (declared.isEmpty()) return emptyList()

        val goalVariables = parsed.variables.filterNot { it.isAnonymous }.distinct().toList()
        // The engine leaves a lone _ out of its answers, so each one gets a named variable of its own. Left as _,
        // two answers that differ only there would fill in to the same goal and one of them would be dropped.
        val anonymous = parsed.variables.filter { it.isAnonymous }.toList()
        val named = anonymous.map { Var.of(ANONYMOUS_NAME) }
        val namedGoal = parsed.apply(Substitution.of(anonymous.zip(named).toMap())) as Struct

        val seen = HashSet<String>()
        val conclusions = ArrayList<Evidence.Derived>()
        for (answer in engine.solveTerm(namedGoal)) {
            if (conclusions.size >= goal.limit) break
            val filled = namedGoal.apply(answer) as Struct
            if (!seen.add(termKey(filled))) continue
            val facts = declared.firstNotNullOfOrNull { trace(it, namedGoal, filled) } ?: continue
            val values = goalVariables.associate { it.name to it.apply(answer) }
            // The text shown for an answer that can't be written so it reads back would name another term.
            val text = conclusionText(goal.goal, values, named.map { it.apply(answer) }, filled)
            if (text == null) {
                logger.warn(
                    "Left out an answer to {}: its conclusion text doesn't read back as the term proved",
                    goal.goal,
                )
                continue
            }
            conclusions += derive(goal, parsed, text, values, facts)
        }
        return conclusions
    }

    /**
     * Finds the recorded facts behind the first citable proof of one answer.
     *
     * @param declaration the clause to replay
     * @param namedGoal the goal as asked, with each anonymous variable given a name
     * @param filled the answer the replay must reach
     * @return the cited facts, or null when no matching proof has a recorded fact for every input
     */
    private fun trace(declaration: ParsedDeclaration, namedGoal: Struct, filled: Struct): List<CitableFact>? {
        if (declaration.inputs.isEmpty()) return null
        // Match the goal first so guards see the caller's bindings. Check the answer after the body runs.
        val query = Tuple.wrapIfNeeded(
            listOf(Struct.of("=", declaration.head, namedGoal)) + declaration.body +
                Struct.of("=", declaration.head, filled)
        ) as Struct

        val openAnswerKey = if (filled.isGround) null else termKey(filled)
        for (proof in engine.solveTerm(query)) {
            // Unification can bind a free variable or make two independent variables equal.
            if (openAnswerKey != null && termKey(declaration.head.apply(proof)) != openAnswerKey) continue
            val perInput = declaration.inputs.map { citableFactsFor(it.apply(proof)) }
            if (perInput.any { it.isEmpty() }) continue
            return perInput.flatten()
        }
        return null
    }

    // Recorded facts only hold atoms, so an input whose arguments aren't all atoms can't be any of them. Each atom is
    // compared by the value Prolog read, so how the declaration quoted or escaped it doesn't matter.
    private fun citableFactsFor(input: Term): List<CitableFact> {
        val struct = input as? Struct ?: return emptyList()
        val atoms = struct.args.map { (it as? Atom)?.value ?: return emptyList() }
        return citableFacts[FactKey(struct.functor, atoms)].orEmpty()
    }

    private fun derive(
        goal: RuleGoal,
        parsed: Struct,
        conclusion: String,
        values: Map<String, Term>,
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
            .withRuleId("${parsed.functor}/${parsed.arity}")
            .withExpression(goal.goal)
        // A variable the answer left unbound has no value to report.
        values.forEach { (name, value) ->
            if (value !is Var) rule = rule.withParameter("binding.$name", bindingText(value))
        }
        rule = rule.withParameter("theory", theoryHash)

        // Items worked out at the same instant as the confidence above, so the two agree.
        val converter = EvidenceConverter.create().withClock(Clock.fixed(now, ZoneOffset.UTC))
        val inputs = inputIds.mapNotNull { found[it] }.filter { it.text.isNotBlank() }.map(converter::item)
        return Evidence.derived(conclusion, confidence, rule, inputIds).withInputs(inputs)
    }

    private fun searchedFor(goal: RuleGoal): Searched =
        (goal.contextId?.let { Searched.of(it) } ?: Searched.unscoped()).withQuery(goal.goal)

    private data class FactKey(val predicate: String, val args: List<String>)

    private class CitableFact(val fact: PrologFact, val ids: List<String>)

    // A declaration whose head, inputs and guards share one set of variables, as they would in a single clause. The
    // body holds the inputs and guards in the order they were added, which is the order they run in.
    private class ParsedDeclaration(val head: Struct, val body: List<Term>, val inputs: List<Term>)

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

        private val logger = LoggerFactory.getLogger(PrologEvidenceProvider::class.java)

        // The name given to each lone _. Every one is still its own variable; only the name is shared.
        private const val ANONYMOUS_NAME = "Anon"

        private fun parseTerm(text: String): Term? =
            try {
                Term.parse(text)
            } catch (e: Exception) {
                null
            }

        // Parses each part of a declaration on its own, then joins them up so one name is one variable across the
        // head, inputs and guards. Each lone _ becomes a named variable of its own, so its value comes back.
        private fun parseDeclaration(declaration: RuleDeclaration): ParsedDeclaration? {
            val shared = HashMap<String, Var>()
            fun join(text: String): Term? {
                val term = parseTerm(text) ?: return null
                val renaming = term.variables.distinct().associateWith { variable ->
                    if (variable.isAnonymous) Var.of(ANONYMOUS_NAME)
                    else shared.getOrPut(variable.name) { Var.of(variable.name) }
                }
                return term.apply(Substitution.of(renaming))
            }
            val head = join(declaration.head) as? Struct ?: return null
            val parts = declaration.body.map { (join(it.text) ?: return null) to it.isInput }
            return ParsedDeclaration(head, parts.map { it.first }, parts.filter { it.second }.map { it.first })
        }

        // The text a caller sees for one answer: the goal as written with each variable's value put in its place,
        // kept only if Prolog reads it back as the filled-in term. If it doesn't, each value is put in brackets and
        // checked again, so -N with N as 5 becomes -(5) rather than the number -5. Null when neither reads back.
        private fun conclusionText(
            goal: String,
            values: Map<String, Term>,
            anonymousValues: List<Term>,
            filled: Struct,
        ): String? {
            val key = termKey(filled)
            for (bracketed in listOf(false, true)) {
                val writer = TermWriter(forKey = false)
                val text = fillVariables(goal) { name, anonymousIndex ->
                    val value = if (name == "_") anonymousValues.getOrNull(anonymousIndex) else values[name]
                    value?.let { writer.write(it, quoteAtoms = true) }?.let { if (bracketed) "($it)" else it }
                }
                if (readsBackAs(text, key)) return text
            }
            return null
        }

        private fun readsBackAs(text: String, key: String): Boolean = parseTerm(text)?.let(::termKey) == key

        // Writes [goal] with each variable replaced by the text [value] gives it, leaving quoted text and character
        // codes such as 0'A alone. A variable is a name starting with an upper-case letter or `_`; each lone `_` is
        // passed with its position among the lone `_`s. A variable [value] has nothing for is left as it is. This
        // only has to be right for goals people write: the caller checks that the result reads back.
        private fun fillVariables(goal: String, value: (name: String, anonymousIndex: Int) -> String?): String =
            buildString {
                var quote: Char? = null
                var anonymousIndex = 0
                var i = 0
                while (i < goal.length) {
                    val c = goal[i]
                    if (quote != null) {
                        append(c)
                        when {
                            c == '\\' && i + 1 < goal.length -> append(goal[++i])
                            c == quote && goal.getOrNull(i + 1) == quote -> append(goal[++i])
                            c == quote -> quote = null
                        }
                        i++
                    } else if (c in QUOTES) {
                        quote = c
                        append(c)
                        i++
                    } else if (isNameChar(c)) {
                        var end = i
                        while (end < goal.length && isNameChar(goal[end])) end++
                        val name = goal.substring(i, end)
                        if (name == "0" && goal.getOrNull(end) == '\'') {
                            val codeEnd = characterCodeEnd(goal, end + 1)
                            append(goal, i, codeEnd)
                            i = codeEnd
                            continue
                        }
                        val written = when {
                            name == "_" -> value(name, anonymousIndex++)
                            name[0].isUpperCase() || name[0] == '_' -> value(name, -1)
                            else -> null
                        }
                        append(written ?: name)
                        i = end
                    } else {
                        append(c)
                        i++
                    }
                }
            }

        private val QUOTES = setOf('\'', '"', '`')

        private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        // An identity for a term, equal for two terms exactly when Prolog would call them the same term with
        // variables renamed. Used to drop repeated answers and to check that written text reads back.
        private fun termKey(term: Term): String = TermWriter(forKey = true).write(term, quoteAtoms = true)

        // A binding's value as a rule parameter: an atom's own text, anything else as written in a conclusion.
        private fun bindingText(value: Term): String =
            if (value is Atom) value.value else TermWriter(forKey = false).write(value, quoteAtoms = false)

        private val READABLE_REAL = Regex("-?\\d+\\.\\d+(?:[eE][+-]?\\d+)?")

        // A real as Prolog reads it back. The engine's own display is kept when it already reads back, such as 0.9;
        // anything else, such as 1E-7, is rebuilt from its digits as one digit, a decimal point, the rest and the
        // exponent: 1.0e-7.
        private fun realText(real: Real): String {
            val display = real.value.toString()
            if (READABLE_REAL.matches(display)) return display
            val decimal = BigDecimal(display)
            val digits = decimal.unscaledValue().abs().toString()
            val exponent = digits.length - 1 - decimal.scale()
            val sign = if (decimal.signum() < 0) "-" else ""
            return "$sign${digits.first()}.${digits.drop(1).ifEmpty { "0" }}e$exponent"
        }

        // Writes terms as Prolog text. Compound terms are written by name, such as '-'(5), and lists in brackets,
        // so no operator in the surrounding text can regroup them. With [forKey] every atom is quoted and each real
        // is written by value, giving the identity text termKey compares rather than text to show anyone.
        private class TermWriter(private val forKey: Boolean) {

            // Unbound variables are named by first appearance, so two terms that differ only in variable names
            // write the same.
            private val names = HashMap<Var, String>()

            fun write(term: Term, quoteAtoms: Boolean): String = when (term) {
                is Var -> names.getOrPut(term) { "_V${names.size + 1}" }
                is Integer -> term.value.toString()
                is Real -> if (forKey) "#${BigDecimal(term.value.toString()).stripTrailingZeros()}#" else realText(term)
                is Atom -> if (quoteAtoms || forKey) Struct.enquoteFunctor(Struct.escapeFunctor(term.value))
                    else functor(term.value)
                is Cons -> if (forKey) compound(term) else list(term)
                is Struct -> compound(term)
                else -> term.toString()
            }

            private fun compound(term: Struct): String =
                (if (forKey) Struct.enquoteFunctor(Struct.escapeFunctor(term.functor)) else functor(term.functor)) +
                    term.args.joinToString(", ", "(", ")") { write(it, quoteAtoms = false) }

            private fun list(term: Cons): String {
                val items = term.unfoldedList
                val tail = items.last()
                val head = items.dropLast(1).joinToString(", ") { write(it, quoteAtoms = false) }
                return if (tail is EmptyList) "[$head]" else "[$head | ${write(tail, quoteAtoms = false)}]"
            }

            private fun functor(name: String): String =
                Struct.enquoteFunctorIfNecessary(Struct.escapeFunctorIfNecessary(name))
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
