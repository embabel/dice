// Bulk-clear propositions, cascade-aware. Deletes the matched propositions and their per-proposition
// :Mention nodes, then prunes any :Source left with no remaining DERIVED_FROM edge (shared sources
// still referenced elsewhere survive). Returns the number of propositions deleted.
//
// Params (both optional — pass null to skip that filter; both null = clear everything):
//   $contextId        exact contextId match
//   $contextIdPrefix  contextId STARTS WITH match
//
// Bench: set :param contextId => null; :param contextIdPrefix => null; then run.
MATCH (p:Proposition)
WHERE ($contextId IS NULL OR p.contextId = $contextId)
  AND ($contextIdPrefix IS NULL OR p.contextId STARTS WITH $contextIdPrefix)
OPTIONAL MATCH (p)-[:HAS_MENTION]->(m:Mention)
OPTIONAL MATCH (p)-[:DERIVED_FROM]->(s:Source)
WITH collect(DISTINCT p) AS ps, collect(DISTINCT m) AS ms, collect(DISTINCT s) AS ss
WITH ps, ms, ss, size(ps) AS deleted
FOREACH (x IN ms | DETACH DELETE x)
FOREACH (x IN ps | DETACH DELETE x)
FOREACH (orphan IN [src IN ss WHERE NOT (src)<-[:DERIVED_FROM]-()] | DELETE orphan)
RETURN deleted