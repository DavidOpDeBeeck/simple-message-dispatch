package app.dodb.smd.ticket.drivenadapter.ticketlog;

import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.ticket.drivenadapter.IntegrationTest;
import app.dodb.smd.ticket.drivingport.TicketUser;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static app.dodb.smd.ticket.drivenadapter.DrivenAdapterConfiguration.TICKET_LOG;
import static app.dodb.smd.ticket.drivenadapter.ticketlog.TicketLogIntegrationTest.TestConfig;
import static app.dodb.smd.ticket.test.TicketTestConstants.CUSTOMER_ID;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Import(TestConfig.class)
class TicketLogIntegrationTest {

    static class TestConfig {

        @Bean
        ProcessingGroupsConfigurer drivenAdapterProcessingGroups() {
            return spec -> spec
                .processingGroup(TICKET_LOG).async().await()
                .anyProcessingGroup().disabled();
        }
    }

    private static final TicketUser USER = new TicketUser("agent-7");

    @Autowired
    private EventPublisher eventPublisher;

    @Test
    void publish_ticketOpenedEvent_logsTicketOpened(CapturedOutput output) {
        var ticketId = UUID.randomUUID();
        var event = new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION);

        eventPublisher.publish(event, metadata());

        assertThat(output).contains("Ticket opened: ticketId=" + ticketId + ", title=" + TICKET_TITLE + ", customerId=" + CUSTOMER_ID + ", principal=" + USER);
    }

    @Test
    void publish_ticketAssignedEvent_logsTicketAssigned(CapturedOutput output) {
        var ticketId = UUID.randomUUID();

        eventPublisher.publish(new TicketAssignedEvent(ticketId, TICKET_ASSIGNEE), metadata());

        assertThat(output).contains("Ticket assigned: ticketId=" + ticketId + ", assignee=" + TICKET_ASSIGNEE + ", customerId=" + CUSTOMER_ID + ", principal=" + USER);
    }

    @Test
    void publish_ticketResolvedEvent_logsTicketResolved(CapturedOutput output) {
        var ticketId = UUID.randomUUID();

        eventPublisher.publish(new TicketResolvedEvent(ticketId, TICKET_RESOLUTION), metadata());

        assertThat(output).contains("Ticket resolved: ticketId=" + ticketId + ", customerId=" + CUSTOMER_ID + ", principal=" + USER);
    }

    private static Metadata metadata() {
        return Metadata.at(TIMESTAMP).withPrincipal(USER).withProperty("customerId", CUSTOMER_ID);
    }
}
