#!/usr/bin/env bash
#
# Split PR #73 (feat/source-revision-provenance) into a three-branch stack.
#
# Run from the dice repo root, with the fixed single branch already committed.
# Creates three branches off origin/main, each building on the last:
#
#   pr73/a-source-revision-contract   contract, codec, plumbing, REST/discovery surfaces
#   pr73/b-collector-evidence-ledger  fold/undo ledger + authoritative setProvenance
#   pr73/c-drivine-provenance-identity raw-Cypher entryKey identity, legacy adoption,
#                                      tenant-scoped queries, save() transaction model
#
# Each stage stops for you to run the build and make the commit. Nothing here
# commits or pushes on your behalf.
#
# WHY A SCRIPT AND NOT CHERRY-PICKS: the branch's rework commits (the review-findings
# and compat-gate corrections) each touch all three areas, so no subsequence of the
# 30 commits forms a clean stack. Each branch has to be reconstructed from the final
# tree as a file subset, which is what this does.

set -euo pipefail

SRC_BRANCH="${SRC_BRANCH:-feat/source-revision-provenance}"
BASE="${BASE:-origin/main}"

say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
take() { # take <path> — copy one path from the source branch's tree
  mkdir -p "$(dirname "$1")"
  git show "$SRC_BRANCH:$1" > "$1"
}

# ---------------------------------------------------------------------------
# Stack A — the contract, its codec-free surfaces, and the plumbing that carries it
# ---------------------------------------------------------------------------
say "Stack A: source revision contract + plumbing"
stack_a() {
  git checkout -b pr73/a-source-revision-contract "$BASE"

  for f in \
    dice/src/main/kotlin/com/embabel/dice/provenance/SourceRevisionRef.kt \
    dice/src/main/kotlin/com/embabel/dice/provenance/ProvenanceEntry.kt \
    dice/src/main/kotlin/com/embabel/dice/common/SourceAnalysisContext.kt \
    dice/src/main/kotlin/com/embabel/dice/common/SourceAnalysisRequestEvent.kt \
    dice/src/main/kotlin/com/embabel/dice/common/ConversationAnalysisRequestEvent.kt \
    dice/src/main/kotlin/com/embabel/dice/pipeline/PropositionPipeline.kt \
    dice/src/main/kotlin/com/embabel/dice/proposition/extraction/IncrementalPropositionExtraction.kt \
    dice/src/main/kotlin/com/embabel/dice/incremental/AbstractIncrementalAnalyzer.kt \
    dice/src/main/kotlin/com/embabel/dice/incremental/proposition/PropositionIncrementalAnalyzer.kt \
    dice/src/main/kotlin/com/embabel/dice/query/discovery/DiscoveryDtos.kt \
    dice/src/main/kotlin/com/embabel/dice/web/rest/MemoryDtos.kt \
    dice/src/main/kotlin/com/embabel/dice/web/rest/PropositionPipelineController.kt \
    dice/src/test/java/com/embabel/dice/SourceRevisionJavaInteropTest.java \
    dice/src/test/kotlin/com/embabel/dice/SourceRevisionCompatibilityTest.kt \
    dice/src/test/kotlin/com/embabel/dice/SourceRevisionBinaryCompatibilityTest.kt \
    dice/src/test/kotlin/com/embabel/dice/common/SourceAnalysisRequestEventRevisionTest.kt \
    dice/src/test/kotlin/com/embabel/dice/pipeline/SourceRevisionPipelineTest.kt \
    dice/src/test/kotlin/com/embabel/dice/proposition/extraction/IncrementalPropositionExtractionTest.kt \
    dice/src/test/kotlin/com/embabel/dice/proposition/store/InMemoryPropositionProvenanceTest.kt \
    dice/src/test/kotlin/com/embabel/dice/proposition/store/JsonFilePropositionRepositoryTest.kt \
    dice/src/test/kotlin/com/embabel/dice/provenance/ProvenanceJsonCompatibilityTest.kt \
    dice/src/test/kotlin/com/embabel/dice/provenance/SourceRevisionContractTest.kt \
    dice/src/test/kotlin/com/embabel/dice/query/discovery/DiscoveryDtoLeakTest.kt \
    dice/src/test/kotlin/com/embabel/dice/web/rest/DiscoveryControllerTest.kt \
    dice/src/test/kotlin/com/embabel/dice/web/rest/PropositionPipelineControllerTest.kt \
    dice/src/test/resources/compat/source-revision-legacy-client.kt \
    dice/src/test/resources/provenance/proposition-revisioned.json \
    dice/src/test/resources/provenance/proposition-revisionless.json \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/model/DerivedFrom.kt \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/PropositionGraphMapper.kt \
    docs/design/source-revision-provenance.md \
    docs/design/INDEX.md
  do take "$f"; done

  # The pinned legacy-client jar is binary; take it as a blob.
  mkdir -p dice/src/test/resources/compat
  git show "$SRC_BRANCH:dice/src/test/resources/compat/source-revision-legacy-client.jar" \
    > dice/src/test/resources/compat/source-revision-legacy-client.jar

  python3 - "$SRC_BRANCH" <<'PYEOF'
  import subprocess, sys
  src = sys.argv[1]

  def branch_file(path):
      return subprocess.check_output(['git', 'show', f'{src}:{path}'], text=True)

  # DerivedFrom: stack A carries the revision scalar so Drivine round-trips revisions from
  # day one. Without it, A alone is a lossy intermediate that silently strips them on save.
  # entryKey is storage identity and belongs to stack C.
  p = 'dice-storage/src/main/kotlin/com/embabel/dice/storage/model/DerivedFrom.kt'
  s = open(p).read().replace(
      "    val sourceRevision: String? = null,\n    val entryKey: String? = null,\n",
      "    val sourceRevision: String? = null,\n")
  open(p, 'w').write(s)

  # PropositionGraphMapper: revision mapping only, no storage entry-key codec.
  p = 'dice-storage/src/main/kotlin/com/embabel/dice/storage/PropositionGraphMapper.kt'
  s = open(p).read().replace("            entryKey = provenanceStorageEntryKey(e),\n", "")
  i = s.find("/**\n * Canonical graph relationship identity for provenance.")
  if i > 0:
      s = s[:i].rstrip() + "\n"
  s = s.replace("    internal fun toDerivedFrom", "    private fun toDerivedFrom")
  s = s.replace("    internal fun toProvenanceEntry", "    private fun toProvenanceEntry")
  open(p, 'w').write(s)

  # PropositionRepository: add only the three source queries onto main's version. The
  # provenanceOf/setProvenance delegation to PropositionStore is stack B's promotion.
  rel = 'dice/src/main/kotlin/com/embabel/dice/proposition/PropositionRepository.kt'
  branch = branch_file(rel)
  base = open(rel).read()
  start = branch.find('    // ========================================================================\n    // Source provenance queries')
  end = branch.find('    // ========================================================================\n    // Administrative operations')
  assert start > 0 and end > start, 'source-query block not found'
  block = branch[start:end]
  anchor = base.find('    // ========================================================================\n    // Administrative operations')
  assert anchor > 0, 'administrative anchor not found in base'
  base = base[:anchor] + block + base[anchor:]
  for imp in ('import com.embabel.agent.core.ContextId\n',
              'import com.embabel.dice.provenance.SourceLocator\n',
              'import com.embabel.dice.provenance.SourceRevisionRef\n'):
      if imp not in base:
          base = base.replace('import com.embabel.dice.provenance.ProvenanceEntry\n',
                              imp + 'import com.embabel.dice.provenance.ProvenanceEntry\n', 1)
  open(rel, 'w').write(base)

  # This compat test's `collector trace JSON` case asserts on foldedProvenanceEvidenceKeys,
  # which does not exist until stack B. The case travels with B.
  p = 'dice/src/test/kotlin/com/embabel/dice/SourceRevisionCompatibilityTest.kt'
  s = open(p).read()
  start = s.find("    @Test\n    fun `collector trace JSON keeps readable refs")
  end = s.find("    @Test\n    fun `old Kotlin synthetic constructor")
  assert start > 0 and end > start, 'collector-trace test not found'
  open(p, 'w').write(s[:start] + s[end:])
  print('stack A trims applied')
  PYEOF

  cat <<'EOT'

  Stack A staged in the working tree. Now:
      ./mvnw -B clean verify
      git add -A && git commit
      # suggested subject: Carry an opaque source revision through the provenance contract

  Then re-run this script with STAGE=b to continue.
  EOT

}

# ---------------------------------------------------------------------------
# Stack B — the collector evidence ledger and authoritative provenance replace
#
# Run after stack A is committed:  git checkout -b pr73/b-collector-evidence-ledger
# ---------------------------------------------------------------------------
stack_b() {
  say "Stack B: collector evidence ledger + authoritative setProvenance"
  git checkout -b pr73/b-collector-evidence-ledger

  for f in \
    dice/src/main/kotlin/com/embabel/dice/provenance/ProvenanceEvidenceKey.kt \
    dice/src/main/kotlin/com/embabel/dice/proposition/Proposition.kt \
    dice/src/main/kotlin/com/embabel/dice/proposition/PropositionStore.kt \
    dice/src/main/kotlin/com/embabel/dice/proposition/PropositionRepository.kt \
    dice/src/main/kotlin/com/embabel/dice/spi/CollectorSignals.kt \
    dice/src/main/kotlin/com/embabel/dice/projection/memory/collector/MultiSignalCollectorStrategy.kt \
    dice/src/test/kotlin/com/embabel/dice/provenance/ProvenanceEvidenceKeyTest.kt \
    dice/src/test/kotlin/com/embabel/dice/proposition/PropositionFoldEvidenceTest.kt \
    dice/src/test/kotlin/com/embabel/dice/proposition/PropositionRepositoryDelegationTest.kt \
    dice/src/test/kotlin/com/embabel/dice/projection/memory/collector/MultiSignalCollectorStrategyTest.kt \
    dice/src/test/kotlin/com/embabel/dice/spi/CollectorUndoCapabilityTest.kt \
    dice/src/test/kotlin/com/embabel/dice/SourceRevisionCompatibilityTest.kt \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/CollectorTraceRowMappers.kt \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/DrivineCollectorTraceStore.kt \
    dice-storage/src/test/kotlin/com/embabel/dice/storage/CollectorTraceRowMapperTest.kt \
    dice-storage/src/test/kotlin/com/embabel/dice/storage/DrivineCollectorTraceStoreIntegrationTest.kt
  do take "$f"; done

  python3 - <<'PYEOF'
# This integration test writes two revision-distinct DERIVED_FROM edges to one :Source,
# which only works once stack C's raw-Cypher entryKey identity lands. On A+B the mapping
# still collapses parallel revisions, so the survivor's evidence disappears entirely and
# the assertion fails. The test asserts C's contract through B's API — it travels with C.
p = 'dice-storage/src/test/kotlin/com/embabel/dice/storage/DrivineCollectorTraceStoreIntegrationTest.kt'
s = open(p).read()
marker = "    fun `collector undo removes only the folded revision from persistent provenance`()"
i = s.find(marker)
assert i > 0, 'revision-undo test not found'
start = s.rfind("    @Test", 0, i)
nxt = s.find("    @Test", i)
end = nxt if nxt > 0 else s.rfind("}")
open(p, 'w').write(s[:start] + s[end:])
print('deferred revision-undo integration test to stack C')
PYEOF

  cat <<'EOT'

Stack B staged. Now:
    ./mvnw -B clean verify
    git add -A && git commit
    # suggested subject: Make collector fold and undo revision-aware

EOT
}

# ---------------------------------------------------------------------------
# Stack C — Drivine provenance identity: the risky one
#
# Changes save() transaction semantics for EVERY caller, not just revision users.
# This is the stack that earns its own review pass.
# ---------------------------------------------------------------------------
stack_c() {
  say "Stack C: Drivine provenance identity, adoption, and tenant-scoped queries"
  git checkout -b pr73/c-drivine-provenance-identity

  for f in \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/DrivinePropositionRepository.kt \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/PropositionGraphMapper.kt \
    dice-storage/src/main/kotlin/com/embabel/dice/storage/model/DerivedFrom.kt \
    dice-storage/src/test/kotlin/com/embabel/dice/storage/DrivinePropositionStoreIntegrationTest.kt \
    dice-storage/src/test/kotlin/com/embabel/dice/storage/PropositionGraphMapperRevisionTest.kt \
    dice-storage/src/test/kotlin/com/embabel/dice/storage/DrivineCollectorTraceStoreIntegrationTest.kt \
    dice/src/test/kotlin/com/embabel/dice/SourceRevisionCompatibilityTest.kt \
    dice/src/main/kotlin/com/embabel/dice/proposition/PropositionRepository.kt
  do take "$f"; done

  cat <<'EOT'

Stack C staged. The tree now matches the single fixed branch exactly.
    ./mvnw -B clean verify
    git add -A && git commit
    # suggested subject: Give stored provenance revision-distinct graph identity

EOT
}

case "${STAGE:-a}" in
  a) stack_a ;;
  b) stack_b ;;
  c) stack_c ;;
  *) echo "STAGE must be a, b, or c" >&2; exit 1 ;;
esac
