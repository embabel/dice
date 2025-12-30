package com.embabel.dice.projection.prolog

import it.unibo.tuprolog.core.Struct
import it.unibo.tuprolog.core.Substitution
import it.unibo.tuprolog.core.Term
import it.unibo.tuprolog.core.Var
import it.unibo.tuprolog.core.parsing.ParseException
import it.unibo.tuprolog.core.parsing.parse
import it.unibo.tuprolog.solve.Solution
import it.unibo.tuprolog.solve.Solver
import it.unibo.tuprolog.solve.classic.ClassicSolverFactory
import it.unibo.tuprolog.theory.Theory
import it.unibo.tuprolog.theory.parsing.parse
import org.slf4j.LoggerFactory

/**
 * Query result from Prolog engine.
 *
 * @property success Whether the query succeeded
 * @property bindings Variable bindings from the query (variable name -> value)
 */
data class QueryResult(
    val success: Boolean,
    val bindings: Map<String, String> = emptyMap(),
) {
    companion object {
        val FAILURE = QueryResult(success = false)
    }
}

/**
 * Prolog engine for executing queries against a knowledge base.
 * Wraps tuProlog to provide a simple interface for loading theories and running queries.
 *
 * This class is immutable - asserting facts returns a new instance.
 */
class PrologEngine private constructor(
    private val theoryString: String,
) {
    private val logger = LoggerFactory.getLogger(PrologEngine::class.java)

    private val solver: Solver by lazy {
        val theory = if (theoryString.isNotBlank()) {
            try {
                Theory.parse(theoryString)
            } catch (e: ParseException) {
                logger.error("Failed to parse Prolog theory: {}", e.message)
                Theory.empty()
            }
        } else {
            Theory.empty()
        }
        ClassicSolverFactory.solverWithDefaultBuiltins(staticKb = theory)
    }

    /**
     * Execute a query and return whether it succeeds.
     *
     * @param queryString The Prolog query (without trailing period)
     * @return true if the query has at least one solution
     */
    fun query(queryString: String): Boolean {
        return try {
            val goal = Struct.parse(queryString)
            val solutions = solver.solve(goal).toList()
            val hasSuccess = solutions.any { it is Solution.Yes }
            logger.debug("Query: {} -> {}", queryString, hasSuccess)
            hasSuccess
        } catch (e: ParseException) {
            logger.error("Failed to parse query: {}", queryString, e)
            false
        } catch (e: Exception) {
            logger.error("Error executing query: {}", queryString, e)
            false
        }
    }

    /**
     * Execute a query and return all solutions with variable bindings.
     *
     * @param queryString The Prolog query (without trailing period)
     * @return List of query results, one per solution
     */
    fun queryAll(queryString: String): List<QueryResult> {
        return try {
            val goal = Struct.parse(queryString)
            solver.solve(goal)
                .filter { it is Solution.Yes }
                .map { solution ->
                    val bindings = extractBindings(solution.substitution)
                    QueryResult(success = true, bindings = bindings)
                }
                .toList()
                .ifEmpty { listOf(QueryResult.FAILURE) }
        } catch (e: ParseException) {
            logger.error("Failed to parse query: {}", queryString, e)
            listOf(QueryResult.FAILURE)
        } catch (e: Exception) {
            logger.error("Error executing query: {}", queryString, e)
            listOf(QueryResult.FAILURE)
        }
    }

    /**
     * Execute a query and return the first solution.
     *
     * @param queryString The Prolog query (without trailing period)
     * @return First query result, or failure if no solutions
     */
    fun queryFirst(queryString: String): QueryResult {
        return try {
            val goal = Struct.parse(queryString)
            val solution = solver.solve(goal).firstOrNull { it is Solution.Yes }
            if (solution != null) {
                val bindings = extractBindings(solution.substitution)
                QueryResult(success = true, bindings = bindings)
            } else {
                QueryResult.FAILURE
            }
        } catch (e: ParseException) {
            logger.error("Failed to parse query: {}", queryString, e)
            QueryResult.FAILURE
        } catch (e: Exception) {
            logger.error("Error executing query: {}", queryString, e)
            QueryResult.FAILURE
        }
    }

    /**
     * Find all values for a variable in a query.
     *
     * @param queryString The Prolog query containing the variable
     * @param variableName The variable to extract (e.g., "X")
     * @return List of values bound to the variable across all solutions
     */
    fun findAll(queryString: String, variableName: String): List<String> {
        return queryAll(queryString)
            .filter { it.success }
            .mapNotNull { it.bindings[variableName] }
    }

    private fun extractBindings(substitution: Substitution): Map<String, String> {
        if (substitution is Substitution.Unifier) {
            return substitution.entries
                .filter { (key, _) -> key is Var }
                .associate { (key, value) ->
                    (key as Var).name to termToString(value)
                }
        }
        return emptyMap()
    }

    private fun termToString(term: Term): String {
        return when (term) {
            is it.unibo.tuprolog.core.Atom -> term.value
            is it.unibo.tuprolog.core.Numeric -> term.value.toString()
            is Struct -> if (term.arity == 0) term.functor else term.toString()
            else -> term.toString()
        }
    }

    companion object {
        /**
         * Create an engine from a PrologProjectionResult.
         * Loads the complete theory including facts, confidence, and grounding.
         *
         * @param result The projection result
         * @param schema The schema containing base rules
         * @return A new PrologEngine ready for queries
         */
        fun fromProjection(result: PrologProjectionResult, schema: PrologSchema): PrologEngine {
            val theory = result.toTheory(schema)
            return PrologEngine(theory)
        }

        /**
         * Create an engine from a theory string.
         *
         * @param theory Prolog theory as a string
         * @return A new PrologEngine
         */
        fun fromTheory(theory: String): PrologEngine {
            return PrologEngine(theory)
        }

        /**
         * Create an empty engine (no facts or rules).
         */
        fun empty(): PrologEngine {
            return PrologEngine("")
        }
    }
}
