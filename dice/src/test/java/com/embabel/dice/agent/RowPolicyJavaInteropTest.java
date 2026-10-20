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
package com.embabel.dice.agent;

import com.embabel.dice.proposition.ApprovedText;
import com.embabel.dice.proposition.ContextReadScope;
import com.embabel.dice.proposition.PropositionKey;
import com.embabel.dice.proposition.ScopedReadSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RowPolicyJavaInteropTest {

    @Test
    void projectionGrantAndPolicyAreBuiltAndValidatedFromJava() {
        RowProjection projection = RowProjection.text(ApprovedText.Stored.INSTANCE)
                .withFields(Set.of(MemoryField.ID))
                .withMetadataKeys(Set.of("public"))
                .withEntityHandles(Set.of("h"))
                .withSources(List.of());
        RowGrant grant = RowGrant.of(new PropositionKey("A", "id"), projection, false);
        RowPolicy policy = RowPolicy.of(new ReadPolicyStamp("p", "purpose", "policy", "v1"), List.of(grant));

        policy.validate(ScopedReadSpec.of(ContextReadScope.ofValues(List.of("A"))));

        assertEquals(Set.of(MemoryField.ID), projection.getFields());
        assertSame(ApprovedText.Stored.INSTANCE, projection.getText());
        assertEquals(List.of(grant), policy.getGrants());
        assertThrows(IllegalArgumentException.class, () -> projection.withMetadataKeys(Set.of(" ")));
    }
}
