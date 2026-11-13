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
import org.jmolecules.ddd.types.Identifier;
import org.jmolecules.event.types.DomainEvent;

/**
 * A support ticket: who it is from and what it says.
 *
 * @param id      the ticket's own id
 * @param account the customer account the ticket belongs to
 * @param text    what was written
 */
public record Ticket(TicketId id, AccountId account, String text) {

    /**
     * Identifies one ticket.
     *
     * @param value the ticket number, for example {@code ticket-1042}
     */
    public record TicketId(String value) implements Identifier {
    }

    /**
     * Published when a ticket has been filed.
     *
     * @param ticket the ticket that came in
     */
    public record TicketFiled(Ticket ticket) implements DomainEvent {
    }
}
