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
package example.dice.starter.memory;

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.SourceRevisionQueryCapable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketExtractionTests {

    @Test
    void refusesAFactStoreThatCannotFindFactsByTicket() {

        var store = mock(SourceRevisionQueryCapable.class);
        when(store.getSupportsSourceRevisionQueries()).thenReturn(false);

        assertThatThrownBy(() -> new TicketExtraction(
                mock(PropositionPipeline.class), mock(DataDictionary.class), mock(AccountStores.class), store))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot find facts by the ticket");
    }
}
