package app.dodb.smd.ticket.drivenadapter.ticketview;

import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.ticket.drivenadapter.IntegrationTest;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static app.dodb.smd.ticket.domain.TicketStatus.ASSIGNED;
import static app.dodb.smd.ticket.domain.TicketStatus.OPEN;
import static app.dodb.smd.ticket.domain.TicketStatus.RESOLVED;
import static app.dodb.smd.ticket.drivenadapter.ticketview.TicketViewIntegrationTest.TestConfig;
import static app.dodb.smd.ticket.test.TicketTestConstants.CUSTOMER_ID;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Import(TestConfig.class)
class TicketViewIntegrationTest {

    static class TestConfig {

        @Bean
        ProcessingGroupsConfigurer ticketProcessingGroups() {
            return spec -> spec
                .processingGroup("ticket-view").sync()
                .anyProcessingGroup().disabled();
        }
    }

    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private JdbcTicketViewRepository viewRepository;

    @Test
    void publish_ticketOpenedEvent_updatesViewBeforePublicationReturns() {
        var ticketId = UUID.randomUUID();

        eventPublisher.publish(new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION), metadata());

        assertThat(viewRepository.find(ticketId)).get()
            .extracting(TicketDTO::status, TicketDTO::title, TicketDTO::openedAt)
            .containsExactly(OPEN, TICKET_TITLE, TIMESTAMP);
    }

    @Test
    void publish_ticketAssignedEvent_updatesViewBeforePublicationReturns() {
        var ticketId = UUID.randomUUID();
        viewRepository.open(ticketId, TICKET_TITLE, TICKET_DESCRIPTION, TIMESTAMP);

        eventPublisher.publish(new TicketAssignedEvent(ticketId, TICKET_ASSIGNEE), metadata());

        assertThat(viewRepository.find(ticketId)).get()
            .extracting(TicketDTO::status, TicketDTO::assignee, TicketDTO::assignedAt)
            .containsExactly(ASSIGNED, TICKET_ASSIGNEE, TIMESTAMP);
    }

    @Test
    void publish_ticketResolvedEvent_updatesViewBeforePublicationReturns() {
        var ticketId = UUID.randomUUID();
        viewRepository.open(ticketId, TICKET_TITLE, TICKET_DESCRIPTION, TIMESTAMP);
        viewRepository.assign(ticketId, TICKET_ASSIGNEE, TIMESTAMP);
        var resolvedAt = TIMESTAMP.plusSeconds(1);

        eventPublisher.publish(new TicketResolvedEvent(ticketId, TICKET_RESOLUTION), metadata(resolvedAt));

        assertThat(viewRepository.find(ticketId)).get()
            .extracting(TicketDTO::status, TicketDTO::resolution, TicketDTO::resolvedAt)
            .containsExactly(RESOLVED, TICKET_RESOLUTION, resolvedAt);
    }

    private static Metadata metadata() {
        return metadata(TIMESTAMP);
    }

    private static Metadata metadata(Instant timestamp) {
        return new Metadata(null, timestamp, null, Map.of("customerId", CUSTOMER_ID));
    }
}
