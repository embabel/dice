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
package example.dice.starter.tickets;

import example.dice.starter.customers.AccountId;
import example.dice.starter.tickets.Ticket.TicketFiled;
import example.dice.starter.tickets.Ticket.TicketId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TicketsTests {

    @Test
    void filingATicketPublishesTicketFiled() {

        List<Object> published = new ArrayList<>();
        var tickets = new Tickets(published::add);
        var ticket = new Ticket(new TicketId("ticket-1"), new AccountId("acme"), "Acme is on the Team plan.");

        tickets.file(ticket);

        assertThat(published).containsExactly(new TicketFiled(ticket));
    }
}
