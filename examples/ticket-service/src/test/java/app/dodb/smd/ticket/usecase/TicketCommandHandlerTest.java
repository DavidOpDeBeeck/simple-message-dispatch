package app.dodb.smd.ticket.usecase;

import app.dodb.smd.spring.test.EnableSMDStubs;
import app.dodb.smd.test.SMDTestExtension;
import app.dodb.smd.ticket.domain.Ticket;
import app.dodb.smd.ticket.domain.TicketStatus;
import app.dodb.smd.ticket.drivingport.command.AssignTicketCommand;
import app.dodb.smd.ticket.drivingport.command.OpenTicketCommand;
import app.dodb.smd.ticket.drivingport.command.ResolveTicketCommand;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import app.dodb.smd.ticket.usecase.stubs.InMemoryTicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static app.dodb.smd.ticket.domain.TicketStatus.OPEN;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@EnableSMDStubs
@SpringBootTest(classes = UseCaseConfiguration.class, webEnvironment = NONE)
class TicketCommandHandlerTest {

    @Autowired
    private SMDTestExtension smd;

    @Autowired
    private InMemoryTicketRepository tickets;

    @Test
    void send_openTicketCommand_createsTicketAndPublishesOpenedEvent() {
        var command = new OpenTicketCommand(TICKET_TITLE, TICKET_DESCRIPTION);

        var ticketId = smd.send(command);

        assertThat(tickets.find(ticketId)).get()
            .extracting(Ticket::status, Ticket::title, Ticket::description)
            .containsExactly(OPEN, command.title(), command.description());
        assertThat(smd.getEvents()).containsExactly(
            new TicketOpenedEvent(ticketId, command.title(), command.description())
        );
    }

    @Test
    void send_assignTicketCommand_assignsExistingTicketAndPublishesAssignedEvent() {
        var ticketId = UUID.randomUUID();
        tickets.save(new Ticket(ticketId, TICKET_TITLE, TICKET_DESCRIPTION, OPEN, null, null, 0));
        var command = new AssignTicketCommand(ticketId, TICKET_ASSIGNEE);

        var result = smd.send(command);

        assertThat(result).isEqualTo(ticketId);
        assertThat(tickets.find(ticketId)).get()
            .extracting(Ticket::status, Ticket::assignee)
            .containsExactly(TicketStatus.ASSIGNED, command.assignee());
        assertThat(smd.getEvents()).containsExactly(
            new TicketAssignedEvent(ticketId, command.assignee())
        );
    }

    @Test
    void send_resolveTicketCommand_resolvesAssignedTicketAndPublishesResolvedEvent() {
        var ticketId = UUID.randomUUID();
        tickets.save(new Ticket(ticketId, TICKET_TITLE, TICKET_DESCRIPTION, TicketStatus.ASSIGNED, TICKET_ASSIGNEE, null, 1));
        var command = new ResolveTicketCommand(ticketId, TICKET_RESOLUTION);

        var result = smd.send(command);

        assertThat(result).isEqualTo(ticketId);
        assertThat(tickets.find(ticketId)).get()
            .extracting(Ticket::status, Ticket::resolution)
            .containsExactly(TicketStatus.RESOLVED, command.resolution());
        assertThat(smd.getEvents()).containsExactly(
            new TicketResolvedEvent(ticketId, command.resolution())
        );
    }
}
