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
package example.dice.graph;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.agent.GraphQueryTools;
import com.embabel.dice.common.Relations;
import com.embabel.dice.evidence.Evidence;
import com.embabel.dice.evidence.EvidenceConverter;
import com.embabel.dice.evidence.EvidenceItem;
import com.embabel.dice.evidence.RuleDeclaration;
import com.embabel.dice.evidence.RuleGoal;
import com.embabel.dice.evidence.Searched;
import com.embabel.dice.projection.graph.RelationBasedGraphProjector;
import com.embabel.dice.projection.prolog.DefaultPrologProjector;
import com.embabel.dice.projection.prolog.PrologEvidenceProvider;
import com.embabel.dice.projection.prolog.PrologFact;
import com.embabel.dice.projection.prolog.PrologSchema;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.provenance.ConnectorRef;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.query.graph.GraphQuery;
import com.embabel.dice.query.oracle.PrologTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphReasoningTests {

    private static final String CONTEXT = "support";
    private static final String ACCOUNT_ID = "account_record";
    private static final String ADMIN_ID = "admin_record";
    private static final String PLAN_ID = "plan_record";
    private static final String RULE = """
            approver(Customer, Admin) :-
                has_account(Customer, Account),
                administers(Admin, Account),
                uses(Account, basic).
            """;

    private final Proposition account = fact(ACCOUNT_ID, "Acme has account Support", 0.95, "chat-1",
            new EntityMention("Acme", "Customer", "acme", MentionRole.SUBJECT),
            new EntityMention("Support", "Account", "support_account", MentionRole.OBJECT));
    private final Proposition admin = fact(ADMIN_ID, "Dana administers Support", 0.9, "chat-2",
            new EntityMention("Dana", "Contact", "dana", MentionRole.SUBJECT),
            new EntityMention("Support", "Account", "support_account", MentionRole.OBJECT));
    private final Proposition plan = fact(PLAN_ID, "Support uses Basic", 0.8, "chat-2",
            new EntityMention("Support", "Account", "support_account", MentionRole.SUBJECT),
            new EntityMention("Basic", "Plan", "basic", MentionRole.OBJECT));

    private final InMemoryPropositionRepository store = new InMemoryPropositionRepository();

    GraphReasoningTests() {
        List.of(account, admin, plan).forEach(store::save);
    }

    @Test
    void followsAPathAndTracesAnEdgeToItsChat() {
        var graph = GraphQuery.of(store, CONTEXT, 2);
        var tools = new GraphQueryTools(graph);

        assertThat(text(tools.entityNeighborhood("acme", 1))).contains("support_account", account.getText());
        assertThat(graph.pathBetween("acme", "dana", null)).singleElement().satisfies(path -> {
            assertThat(path.getEntityIds()).containsExactly("acme", "support_account", "dana");
            assertThat(path.getEdges()).extracting(Proposition::getId).containsExactly(ACCOUNT_ID, ADMIN_ID);
        });
        assertThat(text(tools.pathBetween("acme", "dana"))).contains(account.getText(), admin.getText());
        assertThat(text(tools.whyExplain(ADMIN_ID))).contains(admin.getText(), "grounding chunks: chat-2");
        assertThat(graph.whyExplain(ADMIN_ID).getProvenanceEntries()).extracting(ProvenanceEntry::getLocator)
                .containsExactly(new ConnectorRef("support-chat", "chat-2"));
    }

    @Test
    void derivesAnApproverAndCitesTheThreeRecordedClaims() {
        var relations = Relations.empty()
                .withSemanticBetween("Customer", "Account", "has account", "account ownership")
                .withSemanticBetween("Contact", "Account", "administers", "account administration")
                .withSemanticBetween("Account", "Plan", "uses", "current subscription");
        var recorded = store.query(PropositionQuery.againstContext(CONTEXT));
        var dictionary = DataDictionary.fromClasses("support");
        var projector = RelationBasedGraphProjector.from(relations);
        var schema = PrologSchema.Companion.withRules(RULE, List.of());
        var prologProjector = new DefaultPrologProjector(
                /* graphProjector */ null, schema, /* includeConfidence */ true, /* includeGrounding */ true);
        var declaration = RuleDeclaration.of("approver(Customer, Admin)")
                .withInput("has_account(Customer, Account)")
                .withInput("administers(Admin, Account)")
                .withInput("uses(Account, basic)");
        var goal = RuleGoal.of("approver(acme, Admin)").withContext(CONTEXT);

        var defaultProjection = prologProjector.projectAll(projector.projectAll(recorded, dictionary).getProjected());
        assertThat(defaultProjection.getFacts()).extracting(PrologFact::getPredicate)
                .containsExactlyInAnyOrder("has_account", "administers");
        var defaultProvider = PrologEvidenceProvider.of(defaultProjection, schema)
                .withDeclaration(declaration)
                .withRepository(store);
        assertThat(defaultProvider.evidence(goal)).isInstanceOfSatisfying(Evidence.Missing.class,
                evidence -> assertThat(evidence.getSearched().getQuery()).isEqualTo("approver(acme, Admin)"));

        var edges = projector.withDefaultPolicy(0.8)
                .projectAll(recorded, dictionary)
                .getProjected();
        var projection = prologProjector.projectAll(edges);
        var tools = new PrologTools(projection, schema, Map.of());

        assertThat(projection.getFacts()).extracting(PrologFact::getPredicate)
                .containsExactlyInAnyOrder("has_account", "administers", "uses");
        assertThat(tools.queryProlog("approver(acme, Admin)")).contains("dana");
        assertThat(tools.checkFact("approver(acme, dana)")).contains("Yes");

        var provider = PrologEvidenceProvider.of(projection, schema)
                .withDeclaration(declaration)
                .withRepository(store);

        var stored = EvidenceConverter.create().fromPropositions(recorded, Searched.of(CONTEXT));
        assertThat(stored).isInstanceOf(Evidence.Recorded.class);
        assertThat(provider.evidence(goal))
                .isInstanceOfSatisfying(Evidence.Derived.class, conclusion -> {
                    assertThat(conclusion.getConclusion()).isEqualTo("approver(acme, 'dana')");
                    assertThat(conclusion.getRule().getRuleId()).isEqualTo("approver/2");
                    assertThat(conclusion.getRule().getParameters()).containsEntry("binding.Admin", "dana");
                    assertThat(conclusion.getInputIds()).containsExactlyInAnyOrder(ACCOUNT_ID, ADMIN_ID, PLAN_ID);
                    assertThat(conclusion.getInputs()).extracting(EvidenceItem::getText)
                            .containsExactlyInAnyOrder(account.getText(), admin.getText(), plan.getText());
                    assertThat(conclusion.getConfidence()).isEqualTo(0.8);
                });
        assertThat(store.query(PropositionQuery.againstContext(CONTEXT))).hasSize(3);

        var missing = RuleGoal.of("approver(westwind, Admin)").withContext(CONTEXT);
        assertThat(provider.evidence(missing)).isInstanceOfSatisfying(Evidence.Missing.class,
                evidence -> assertThat(evidence.getSearched().getQuery()).isEqualTo("approver(westwind, Admin)"));
    }

    private static Proposition fact(String id, String text, double confidence, String chat, EntityMention... mentions) {
        return Proposition.draft(CONTEXT, text, confidence)
                .withId(id)
                .withDecay(0.0)
                .withMentions(List.of(mentions))
                .withGrounding(List.of(chat))
                .withProvenance(List.of(new ProvenanceEntry(new ConnectorRef("support-chat", chat), chat)))
                .toProposition();
    }

    private static String text(Tool.Result result) {
        return ((Tool.Result.Text) result).getContent();
    }
}
