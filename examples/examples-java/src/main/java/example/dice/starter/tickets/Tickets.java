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

import example.dice.starter.tickets.Ticket.TicketFiled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Files tickets. Filing publishes a {@link TicketFiled} event, which other modules listen for.
 */
@Service
public class Tickets {

    private final ApplicationEventPublisher events;

    /**
     * @param events where {@link TicketFiled} is published
     */
    Tickets(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Files a ticket.
     *
     * @param ticket the ticket that came in
     */
    public void file(Ticket ticket) {
        events.publishEvent(new TicketFiled(ticket));
    }
}
