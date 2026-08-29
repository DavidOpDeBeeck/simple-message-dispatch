package app.dodb.smd.ticket;

import app.dodb.smd.ticket.drivingadapter.TicketController;
import app.dodb.smd.ticket.drivingadapter.TicketController.OpenTicketRequest;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;

import static app.dodb.smd.ticket.domain.TicketStatus.ASSIGNED;
import static app.dodb.smd.ticket.domain.TicketStatus.OPEN;
import static app.dodb.smd.ticket.domain.TicketStatus.RESOLVED;
import static app.dodb.smd.ticket.test.TicketTestConstants.CUSTOMER_ID;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(classes = TicketApplication.class, webEnvironment = RANDOM_PORT)
class TicketApiAcceptanceTest {

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .build();
    }

    @Test
    void call_ticketApi_completesLifecycleAndExposesProjections() {
        var createdTicket = client.post()
            .uri("/tickets")
            .contentType(APPLICATION_JSON)
            .header("X-Customer-Id", CUSTOMER_ID)
            .body(new OpenTicketRequest(TICKET_TITLE, TICKET_DESCRIPTION))
            .retrieve()
            .body(TicketDTO.class);

        assertThat(createdTicket).isNotNull();
        assertThat(createdTicket.ticketId()).isNotNull();
        assertThat(createdTicket.title()).isEqualTo(TICKET_TITLE);
        assertThat(createdTicket.description()).isEqualTo(TICKET_DESCRIPTION);
        assertThat(createdTicket.status()).isEqualTo(OPEN);
        assertThat(createdTicket.assignee()).isNull();
        assertThat(createdTicket.resolution()).isNull();
        assertThat(createdTicket.openedAt()).isNotNull();
        assertThat(createdTicket.assignedAt()).isNull();
        assertThat(createdTicket.resolvedAt()).isNull();

        var ticketId = createdTicket.ticketId();
        var assignedTicket = client.post()
            .uri("/tickets/{ticketId}/assign", ticketId)
            .contentType(APPLICATION_JSON)
            .header("X-Customer-Id", CUSTOMER_ID)
            .body(new TicketController.AssignTicketRequest(TICKET_ASSIGNEE))
            .retrieve()
            .body(TicketDTO.class);

        assertThat(assignedTicket).isNotNull();
        assertThat(assignedTicket.ticketId()).isEqualTo(ticketId);
        assertThat(assignedTicket.status()).isEqualTo(ASSIGNED);
        assertThat(assignedTicket.assignee()).isEqualTo(TICKET_ASSIGNEE);
        assertThat(assignedTicket.resolution()).isNull();
        assertThat(assignedTicket.assignedAt()).isNotNull();
        assertThat(assignedTicket.resolvedAt()).isNull();

        var resolvedTicket = client.post()
            .uri("/tickets/{ticketId}/resolve", ticketId)
            .contentType(APPLICATION_JSON)
            .header("X-Customer-Id", CUSTOMER_ID)
            .body(new TicketController.ResolveTicketRequest(TICKET_RESOLUTION))
            .retrieve()
            .body(TicketDTO.class);

        assertThat(resolvedTicket).isNotNull();
        assertThat(resolvedTicket.ticketId()).isEqualTo(ticketId);
        assertThat(resolvedTicket.status()).isEqualTo(RESOLVED);
        assertThat(resolvedTicket.assignee()).isEqualTo(TICKET_ASSIGNEE);
        assertThat(resolvedTicket.resolution()).isEqualTo(TICKET_RESOLUTION);
        assertThat(resolvedTicket.resolvedAt()).isNotNull();

        var latestTicket = client.get()
            .uri("/tickets/{ticketId}", ticketId)
            .header("X-Customer-Id", CUSTOMER_ID)
            .retrieve()
            .body(TicketDTO.class);

        assertThat(latestTicket).isEqualTo(resolvedTicket);

        await().untilAsserted(() -> {
            var ticketActivity = client.get()
                .uri("/tickets/{ticketId}/activity", ticketId)
                .header("X-Customer-Id", CUSTOMER_ID)
                .retrieve()
                .body(new ParameterizedTypeReference<List<TicketActivityDTO>>() {
                });

            assertThat(ticketActivity).hasSize(3);
            assertThat(ticketActivity).extracting(TicketActivityDTO::ticketId).containsOnly(ticketId);
            assertThat(ticketActivity).extracting(TicketActivityDTO::eventType).containsExactly("TicketOpened", "TicketAssigned", "TicketResolved");
            assertThat(ticketActivity).extracting(TicketActivityDTO::customerId).containsOnly(CUSTOMER_ID);
            assertThat(ticketActivity).extracting(TicketActivityDTO::details).containsExactly("title=" + TICKET_TITLE, "assignee=" + TICKET_ASSIGNEE, "resolution=" + TICKET_RESOLUTION);
            assertThat(ticketActivity).allSatisfy(entry -> {
                assertThat(entry.eventId()).isNotNull();
                assertThat(entry.timestamp()).isNotNull();
            });
        });
    }
}
