# Scoped proposition reads

An agent often works for someone who can see more than one context: their own memories, a shared
team context, a project they joined. An ordinary query reads one context at a time, which pushes
hosts toward running several queries and merging the results. Merging is where it goes wrong. Each
query ranks and truncates on its own, so the merged list is not the best answer over the combined
set, and it is easy for model input to end up choosing which contexts get read. Scoped reads give
the host one reader over exactly the contexts it has approved. The
[user guide](../../dice-user-guide/src/main/asciidoc/how-to/read-across-contexts.adoc) shows how to
use them; this note explains the choices behind them.

## The host owns the scope

A context ID names data. Knowing one doesn't give anyone the right to read it. So the reader takes a
finite, non-empty `ContextReadScope` that the host resolves before any model sees a tool, and
nothing a tool call sends can change it. Narrowing only ever adds conditions: `restrict` ANDs
criteria onto what is already there, grants intersect, and exclusions accumulate. Code further along
can make a read smaller and never larger. Rows are named by `PropositionKey(context, id)`.
Proposition IDs are UUIDs and every store keys rows by ID alone, so a second save under the same ID
replaces the first whatever its context. The context in the key is there for the policy layer: a
grant can't name a row outside the scope.

## Filter first, then rank

The usual way to filter vector search is to ask the index for the top K and drop what doesn't match.
That quietly starves the result. If the ten nearest rows all belong to someone else, a filtered top
ten comes back empty even though matching rows exist. Every scoped operation applies the scope,
criteria, grants and exclusions in the backend before it ranks, counts or limits. The older filtered
overload, `VectorSearchCapable.findSimilarWithScores(request, query)`, used to filter after the fact
by default. Its default now throws `FilteredVectorUnsupportedException`, so a store either filters
first or says it can't. The Neo4j store filters first on both of its paths. It runs in the database
when it has a native read executor, the query names a context and the caller has no read-write
transaction open. Otherwise it scans the matching rows in the caller's transaction, so it sees that
transaction's own writes; that path is held to the candidate limit and has no timeout.
`findApproximateSimilarWithScores` is the index-backed alternative for callers that can accept
approximate neighbours, such as revision; it keeps widening its index search until it has enough
matching rows.

## Exact, bounded and loud

A global approximate index is tuned for the whole store, and its recall over a small eligible subset
is unknown. So scoped vector search scores every eligible row exactly. That costs time in proportion
to the eligible set, which is why each search carries an `ExactVectorBudget`. When the eligible set
is bigger than the budget, the search fails instead of scoring a sample. A partial answer that looks
complete is worse than an error, because an agent treats a missing memory as something nobody knows.
One kind of row is skipped on purpose: a stored embedding that can't be scored, because it has the
wrong length or a NaN or infinite value. One corrupt row shouldn't take down every search in its
scope, so it is logged and still counted against the budget. A bad query embedding is the caller's
problem and fails the search. The same thinking runs through the rest of the design: a store that
can't run a scoped read says so with a `ScopedReadFailure` code, a store failure keeps its cause,
and nothing falls back to scanning the whole store or returning an empty list.

## The same answer on every backend

The in-memory, JSON-file, Neo4j and FalkorDB stores each run scoped reads in their own engine, so
they have to agree on details a test would otherwise miss. Ties break by context ID and then
proposition ID, compared by Unicode code point, which is how the databases compare strings anyway. A
read session fixes the instant and decay rate used for effective confidence, so a count and a query
in the same session agree on which rows qualify. It doesn't freeze the data between calls.

## Memory on top

`ScopedMemory` and `PolicyMemory` build on the reader. Each search tier (vector, keyword, related
entities) contributes a bounded pool of candidates, and reciprocal rank fusion ranks their union.
That gives the best answer from bounded pools and makes no claim to an exact fusion over every
authorized row. Memories already published into the prompt are left out of later tool calls, and
only after publication succeeds, so a failed render never hides a memory the model didn't see.

Policies add row grants and field projection for hosts that must show some rows only in part. In
strict mode a hidden field can't influence results either: a filter, order or search that would read
a field the grant hides fails before the store is touched. Replacing a policy governs future reads
only. Text a model has already seen can't be recalled.
