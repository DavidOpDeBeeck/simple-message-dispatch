package app.dodb.smd.ticket.drivenadapter.ticketactivity;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.channel.EventStore;
import app.dodb.smd.ticket.drivenadapter.IntegrationTest;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.UUID;

import static app.dodb.smd.ticket.drivenadapter.ticketactivity.TicketActivityIntegrationTest.TestConfig;
import static app.dodb.smd.ticket.test.TicketTestConstants.CUSTOMER_ID;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@Import(TestConfig.class)
class TicketActivityIntegrationTest {

    static class TestConfig {

        @Bean
        ProcessingGroupsConfigurer ticketProcessingGroups(EventStore eventStore) {
            return spec -> spec
                .processingGroup("ticket-activity").source(eventStore)
                .anyProcessingGroup().disabled();
        }
    }

    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private JdbcTicketActivityRepository activityRepository;

    @Test
    void publish_ticketOpenedEvent_recordsActivityWithMessageMetadata() {
        var ticketId = UUID.randomUUID();
        var event = new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION);
        var eventMessage = EventMessage.from(event, metadata());

        eventPublisher.publish(eventMessage);

        await().untilAsserted(() -> assertThat(activityRepository.findAll(ticketId)).singleElement()
            .extracting(
                TicketActivityDTO::ticketId,
                TicketActivityDTO::eventId,
                TicketActivityDTO::eventType,
                TicketActivityDTO::customerId,
                TicketActivityDTO::timestamp,
                TicketActivityDTO::details)
            .containsExactly(
                ticketId,
                eventMessage.messageId().value(),
                "TicketOpened",
                CUSTOMER_ID,
                TIMESTAMP,
                "title=" + TICKET_TITLE
            ));
    }

    @Test
    void publish_ticketAssignedEvent_recordsActivityWithMessageMetadata() {
        var ticketId = UUID.randomUUID();
        var event = new TicketAssignedEvent(ticketId, TICKET_ASSIGNEE);
        var eventMessage = EventMessage.from(event, metadata());

        eventPublisher.publish(eventMessage);

        await().untilAsserted(() -> assertThat(activityRepository.findAll(ticketId)).singleElement()
            .extracting(
                TicketActivityDTO::ticketId,
                TicketActivityDTO::eventId,
                TicketActivityDTO::eventType,
                TicketActivityDTO::customerId,
                TicketActivityDTO::timestamp,
                TicketActivityDTO::details)
            .containsExactly(
                ticketId,
                eventMessage.messageId().value(),
                "TicketAssigned",
                CUSTOMER_ID,
                TIMESTAMP,
                "assignee=" + TICKET_ASSIGNEE
            ));
    }

    @Test
    void publish_ticketResolvedEvent_recordsActivityWithMessageMetadata() {
        var ticketId = UUID.randomUUID();
        var eventMessage = EventMessage.from(new TicketResolvedEvent(ticketId, TICKET_RESOLUTION), metadata());

        eventPublisher.publish(eventMessage);

        await().untilAsserted(() -> assertThat(activityRepository.findAll(ticketId)).singleElement()
            .extracting(
                TicketActivityDTO::ticketId,
                TicketActivityDTO::eventId,
                TicketActivityDTO::eventType,
                TicketActivityDTO::customerId,
                TicketActivityDTO::timestamp,
                TicketActivityDTO::details)
            .containsExactly(
                ticketId,
                eventMessage.messageId().value(),
                "TicketResolved",
                CUSTOMER_ID,
                TIMESTAMP,
                "resolution=" + TICKET_RESOLUTION
            ));
    }

    private static Metadata metadata() {
        return new Metadata(null, TIMESTAMP, null, Map.of("customerId", CUSTOMER_ID));
    }
}
