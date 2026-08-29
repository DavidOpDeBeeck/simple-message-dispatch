package app.dodb.smd.ticket.usecase;

import app.dodb.smd.spring.test.EnableSMDStubs;
import app.dodb.smd.test.SMDTestExtension;
import app.dodb.smd.ticket.domain.TicketStatus;
import app.dodb.smd.ticket.drivingport.query.GetTicketActivityQuery;
import app.dodb.smd.ticket.drivingport.query.GetTicketQuery;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import app.dodb.smd.ticket.usecase.stubs.InMemoryTicketActivityRepository;
import app.dodb.smd.ticket.usecase.stubs.InMemoryTicketViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static app.dodb.smd.ticket.test.TicketTestConstants.CUSTOMER_ID;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@EnableSMDStubs
@SpringBootTest(classes = UseCaseConfiguration.class, webEnvironment = NONE)
class TicketQueryHandlerTest {

    @Autowired
    private SMDTestExtension smd;

    @Autowired
    private InMemoryTicketViewRepository views;

    @Autowired
    private InMemoryTicketActivityRepository activity;

    @Test
    void send_getTicketQuery_returnsTicketView() {
        var ticketId = UUID.randomUUID();
        var expected = new TicketDTO(
            ticketId,
            TICKET_TITLE,
            TICKET_DESCRIPTION,
            TicketStatus.OPEN,
            null,
            null,
            TIMESTAMP,
            null,
            null
        );
        views.stub(ticketId, expected);

        var actual = smd.send(new GetTicketQuery(ticketId));

        assertThat(actual).contains(expected);
    }

    @Test
    void send_getTicketActivityQuery_returnsTicketActivity() {
        var ticketId = UUID.randomUUID();
        var expected = List.of(new TicketActivityDTO(
            ticketId,
            UUID.randomUUID(),
            "TicketOpened",
            CUSTOMER_ID,
            TIMESTAMP,
            "title=" + TICKET_TITLE
        ));
        activity.stub(ticketId, expected);

        var actual = smd.send(new GetTicketActivityQuery(ticketId));

        assertThat(actual).containsExactlyElementsOf(expected);
    }
}
