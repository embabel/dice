# Split the README into a docs tree

Decides the `docs/` layout, which README section lands on which page, the shape of the quickstart
and feature pages, and the rule that keeps pages current. Writing the pages is out of scope.

## Target tree

| Directory | What goes there |
|---|---|
| `docs/README.adoc` | What DICE is, when to use it, links into the tree. |
| `docs/quickstart/` | One page, one path: dependencies through first report in 15 minutes. |
| `docs/concepts/` | Five pages, read in order, propositions through knowledge hygiene. |
| `docs/how-to/` | Task-shaped pages titled by what the reader wants, prerequisites at the top. |
| `docs/features/` | One page per opt-in surface, activation condition first. |
| `docs/reference/` | Configuration properties, package structure, REST endpoints. |
| `docs/production/` | Backend migration, decay settings, concurrency, LLM cost, observability, backup. |
| `docs/support/` | Compatibility matrix and FAQ. |

| Location | Audience | Question it answers |
|---|---|---|
| `docs/design/` (19 notes and an index, unchanged) | DICE contributors | Why is it built this way? |
| `docs/` | DICE consumers | How do I use it? |
| `specs/` | Us | What are we building, and why does it matter commercially? |
| `README.md` | Anyone landing on the repo | What is this, and where do I start? |

## README migration

`README.md` is 2621 lines, target around 250. Line numbers move, so re-check a row before acting.

| README section (lines) | Fate |
|---|---|
| What is DICE, benefits table, architecture overview, design notes (24-116) | Keep, trimmed. This is the landing page's job. |
| Real-world example: Impromptu (117-126) | Keep, cut to a paragraph plus a link. |
| Pipeline setup, conversation analysis (127-174) | Move to `docs/quickstart/`. |
| Key features, proposition pipeline, content dedup, mention filtering (175-489) | Move to `docs/concepts/propositions.adoc`, `docs/how-to/extract-from-documents.adoc`, `docs/how-to/mention-filtering.adoc`. |
| Entity extraction, entity resolution, resolution service (490-1225) | Move to `docs/concepts/entity-resolution.adoc` and `docs/how-to/tune-entity-resolution.adoc`. Largest block, split it. |
| Source analysis context, `ContextId`, `PropositionQuery` (1226-1419) | Move to `docs/concepts/context-and-schema.adoc`, `docs/how-to/query-propositions.adoc`. |
| Relations, projector architecture, graph and Prolog projection (1420-1647) | Move to `docs/concepts/storage-and-projections.adoc`, `docs/how-to/project-to-graph.adoc`, `docs/features/prolog-inference.adoc`. |
| Agent memory, memory projection, memory maintenance (1648-1979) | Move to `docs/how-to/agent-memory.adoc`, `docs/concepts/knowledge-hygiene.adoc`. |
| Proposition operations, Oracle (1980-2090) | Move to `docs/how-to/query-propositions.adoc`, `docs/how-to/oracle.adoc`. |
| Package structure (2091-2205) | Move to `docs/reference/package-structure.adoc`. |
| REST API and endpoints (2206-2334) | Move to `docs/features/web-api.adoc`. |
| Spring Boot integration, graph-backed storage, API-key security (2335-2572) | Move to `docs/how-to/choose-a-backend.adoc`, `docs/reference/configuration-properties.adoc`, `docs/features/web-api.adoc`. |
| Installation (2573-2585) | Keep as coordinates only. The working version lives in the quickstart. |
| Technology stack, references, license (2586-2621) | Keep. |

Content moves: a section is deleted from the README as it lands under `docs/`, so one copy exists,
and it leaves a one-line link where it was so an existing bookmark still lands somewhere useful.
One PR per destination page.

## Concept pages

Read in order. Each page uses terms the page before it defines, and ends with a "Try it now" block
of five to ten lines that runs against the quickstart's setup, with the output to expect.

1. `propositions.adoc`: claims as the system of record, with confidence, importance and decay.
2. `entity-resolution.adoc`: mentions matched to entities or minted as new, and the resolver chain.
3. `storage-and-projections.adoc`: the `PropositionStore` SPI, its repositories, and the views.
4. `context-and-schema.adoc`: `ContextId` scoping, the `DataDictionary`, and `SchemaAdherence`.
5. `knowledge-hygiene.adoc`: admission gates, reclamation and consolidation.

## Feature pages

One page per opt-in surface, opening with the activation condition: the exact bean or property that
switches the feature on. Template, in order:

- Availability: DICE version, modules, cost to add.
- Activation condition: the bean or property, with the value that turns it on.
- When to use it, and when to leave it off.
- Impact: latency, memory, extra infrastructure, extra LLM calls.
- How to enable: full working configuration.
- Example: real code with real output.

| Surface | Activation condition |
|---|---|
| Graph-backed storage | `embabel.dice.store.type=graph` |
| Vector index on Neo4j | `embabel.dice.store.vector-index.enabled=true` |
| Web API | `@Import(DiceRestConfiguration.class)` plus the beans the controllers need |
| API-key security | `dice.security.api-key.enabled=true` |
| Decay and stale pruning | `embabel.dice.store.decay.enabled=true`, with `prune-stale` false by default |
| Multi-signal collector | `embabel.dice.collector.enabled`, on unless set to false |
| Prolog inference | A `PrologProjector` bean. Experimental. |
| Concurrent extraction | A parallel or batched `ExtractionExecutionStrategy` on the pipeline |

## Quickstart

DICE publishes `dice`, `dice-ingestion`, `dice-storage`, `dice-storage-autoconfigure` and
`dice-report`, and no aggregator starter. Decide the coordinates before writing the page:

- Add a `dice-spring-boot-starter` depending on `dice-storage-autoconfigure` and `dice-report`,
  publish it, and the quickstart uses one coordinate. Needs a release first.
- Write against `dice-storage-autoconfigure` and `dice-report`, both explicit, with a comment
  saying what each buys. Two coordinates, available today.

1. Dependencies: the coordinates chosen above, at the current DICE version.
2. Configuration: an LLM provider inherited from embabel-agent, and the default in-memory store.
3. What autoconfiguration provides: the beans that now exist and what each is for.
4. Extract: one paragraph of text through `PropositionPipeline`, with confidence printed.
5. Persist: `persist(propositionRepository, namedEntityDataRepository)` on the `PersistablePropositions` the pipeline hands back unsaved.
6. Query: retrieve what was stored, by entity and by `ContextId`.
7. Report: produce one human-readable artifact through `dice-report`.
8. Where to go next: links into `docs/concepts/` and `docs/how-to/`.

Constraints:

- No Neo4j, no Docker, no external resource to create.
- `InMemoryPropositionRepository` needs an `EmbeddingService` for vector search, so the minimal path queries by entity and `ContextId`.
- Every snippet compiles, and a test in `dice-integration-tests` runs the page's exact code.
- Anything needing a decision (backend, resolver chain, schema) takes the default and links out.

## Compatibility matrix

Its own page under `docs/support/`, linked from the README. Four axes, plus a feature-availability
column. Values get pinned at the 1.0 release.

| Axis | What we state |
|---|---|
| embabel-agent | Supported version range per DICE release, with the tested point release named. The build currently tracks 1.5.0-SNAPSHOT. |
| Spring Boot | Minimum and maximum tested, per DICE release. |
| JDK | Baseline is 21, inherited from `embabel-build-parent` and used by CI. Newer JDKs listed as tested or untested. |
| Neo4j | Version floor for the graph backend, reached through Drivine (`drivine4j-spring-boot-starter` 0.0.79). The in-memory backend runs without Neo4j. |

## Docs rule

Every feature PR ships its developer-doc page and its design-doc delta. Consumer-visible behaviour
updates the page under `docs/`, new rationale updates the `docs/design/` note, an opt-in feature
gets a feature page carrying its activation condition, a new property updates
`docs/reference/configuration-properties.adoc`, and a version-support change updates the compatibility
matrix. Internal refactors with no consumer-visible change, test-only changes and build changes are
exempt. Reviewers ask one question: with only this PR's docs, could a consumer use the feature? A no
is a blocking finding. A promise of later docs does not clear it.

## Open questions

- Starter module or two coordinates? Recommendation: two coordinates, which needs no release.
- Which format, and rendered where? Decided: AsciiDoc, matching the framework reference, which
  builds from `embabel-agent-docs/src/main/asciidoc` through the asciidoctor Maven plugin. GitHub
  renders `.adoc` in the repo, so the tree stays readable before any site exists, and the existing
  Markdown under `docs/design/` converts as those notes get touched. Whether DICE renders its own
  site or publishes into the framework's is still open.
- Does DICE ship a CLI (schema validation, drift check)? Recommendation: no CLI before 1.0, and its
  own quickstart if one lands.
- Promote `docs/design/` notes into concept pages? Recommendation: they stay contributor-facing,
  and concept pages lift explanations from them and link back.
