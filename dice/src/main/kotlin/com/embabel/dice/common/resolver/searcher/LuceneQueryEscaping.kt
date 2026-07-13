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
package com.embabel.dice.common.resolver.searcher

/**
 * Characters Lucene's classic query parser treats as syntax, not literal text.
 * Mirrors `org.apache.lucene.queryparser.classic.QueryParserBase.escape` exactly.
 */
private val LUCENE_SPECIAL_CHARS = setOf(
    '\\', '+', '-', '!', '(', ')', ':', '^', '[', ']', '"', '{', '}', '~', '*', '?', '|', '&', '/',
)

/**
 * Escapes Lucene special characters in [s] so it can be dropped into a fulltext
 * query as literal text.
 *
 * Entity names come from arbitrary source text — they can contain any of `+ -
 * ! ( ) : ^ [ ] " { } ~ * ? | & /` and a backslash itself. Those all mean
 * something to Lucene's query syntax. A name that reaches
 * `db.index.fulltext.queryNodes` unescaped can crash the query outright (a
 * dangling `~` or `/` throws a lexical error) or, worse, silently change what
 * the query means (an embedded `"` ends a phrase early). Cypher parameter
 * binding doesn't help here: Neo4j hands the string straight to Lucene's
 * `QueryParser`, which re-parses it as query syntax regardless of how it got
 * there.
 *
 * A name with none of these characters passes through unchanged, so existing
 * plain-name matching behaves exactly as before.
 *
 * We don't depend on Lucene here — dice never talks to it directly, it only
 * ever reaches Lucene indirectly through whichever store backs
 * [com.embabel.agent.rag.service.NamedEntityDataRepository.textSearch] (e.g.
 * Neo4j's fulltext index). Pulling in `lucene-queryparser` for one static
 * method isn't worth the dependency, so this reimplements it.
 */
internal fun luceneEscape(s: String): String = buildString(s.length) {
    for (c in s) {
        if (c in LUCENE_SPECIAL_CHARS) {
            append('\\')
        }
        append(c)
    }
}

/**
 * Builds an exact-phrase Lucene query for [name]: escape first, then wrap in
 * quotes. Escaping first means any quote already in [name] is itself escaped,
 * so it can't close the phrase early — without this ordering, a name like
 * `Acme "Legacy" Corp` would silently turn into three OR'd terms instead of
 * one exact phrase.
 */
internal fun luceneExactPhraseQuery(name: String): String = "\"${luceneEscape(name)}\""
