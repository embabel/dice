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
package com.embabel.dice.audit;

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditJavaInteropTest {

    @Test
    void aHostCanInstallReadAndEraseAuditHistoryUsingStringContextIds() {
        var auditTime = Instant.parse("2026-01-02T00:00:00Z");
        var audit = new InMemoryAuditLog(Clock.fixed(auditTime, ZoneOffset.UTC));
        AuditSink sink = audit;
        AuditLogQuery reader = audit;
        var actor = new AuditActorRef("opaque-java-user");
        AuditContextProvider attribution = context -> new AuditAttribution(actor);
        var repo = AuditingPropositionRepository.wrapping(new InMemoryPropositionRepository(), sink, attribution);
        var proposition = Proposition.create("id", "context", "Known fact", List.of(), 0.9, 1.0,
                null, List.of(), auditTime, auditTime, PropositionStatus.ACTIVE);

        sink.initializeBaseline("context", auditTime, List.of());
        repo.save(proposition);

        var record = reader.history("context", "id").getFirst();
        assertEquals(AuditKind.CREATED, record.getKind());
        assertEquals(actor, record.getAttribution().getActor());
        assertEquals("id", reader.asOf("context", "id", auditTime).getId());
        assertEquals(1, repo.clearByContext("context"));
        assertEquals(AuditKind.ERASURE, reader.query("context", 0, 10).getFirst().getKind());
    }
}
