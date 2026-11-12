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
package com.embabel.dice;

import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.resolver.CandidateBakeoff;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class EscalatingEntityResolverJavaInteropTest {

    private final NamedEntityDataRepository repository = Mockito.mock(NamedEntityDataRepository.class);

    @Test
    void createNeedsOnlyARepository() {
        EntityResolver resolver = EscalatingEntityResolver.create(repository);

        assertNotNull(resolver);
    }

    @Test
    void withoutVectorNeedsOnlyARepository() {
        EntityResolver resolver = EscalatingEntityResolver.withoutVector(repository);

        assertNotNull(resolver);
    }

    @Test
    void bothFactoriesStillTakeABakeoff() {
        CandidateBakeoff bakeoff = Mockito.mock(CandidateBakeoff.class);

        assertNotNull(EscalatingEntityResolver.create(repository, bakeoff));
        assertNotNull(EscalatingEntityResolver.withoutVector(repository, bakeoff));
    }
}
