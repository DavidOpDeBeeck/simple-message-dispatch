package app.dodb.smd.ticket.drivenadapter.customerwebhooks;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.durable.DurableDelivery;
import app.dodb.smd.ticket.drivenadapter.IntegrationTest;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

import static app.dodb.smd.ticket.drivenadapter.customerwebhooks.CustomerWebhooksIntegrationTest.TestConfig;
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
class CustomerWebhooksIntegrationTest {

    static class TestConfig {

        @Bean
        ProcessingGroupsConfigurer ticketProcessingGroups(DurableDelivery durableDelivery) {
            return spec -> spec
                .processingGroup("customer-webhooks").medium(durableDelivery.dispatcher("ticket-service.customer-webhooks"))
                .anyProcessingGroup().disabled();
        }

        @Bean
        @Primary
        FakeCustomerWebhookSender fakeCustomerWebhookSender() {
            return new FakeCustomerWebhookSender();
        }
    }

    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private FakeCustomerWebhookSender webhookSender;

    @Test
    void publish_ticketLifecycle_sendsWebhooksInOrder() {
        var ticketId = UUID.randomUUID();
        var opened = EventMessage.from(new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION), metadata());
        var assigned = EventMessage.from(new TicketAssignedEvent(ticketId, TICKET_ASSIGNEE), metadata());
        var resolved = EventMessage.from(new TicketResolvedEvent(ticketId, TICKET_RESOLUTION), metadata());

        eventPublisher.publish(opened);
        eventPublisher.publish(assigned);
        eventPublisher.publish(resolved);

        await().untilAsserted(() -> assertThat(webhookSender.attemptsFor(ticketId)).containsExactly(
            webhook(opened),
            webhook(assigned),
            webhook(resolved)
        ));
    }

    @Test
    void publish_whenCustomerEndpointFails_retriesBeforeSendingLaterTicketEvents() {
        var ticketId = UUID.randomUUID();
        var opened = EventMessage.from(new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION), metadata());
        var assigned = EventMessage.from(new TicketAssignedEvent(ticketId, TICKET_ASSIGNEE), metadata());
        var resolved = EventMessage.from(new TicketResolvedEvent(ticketId, TICKET_RESOLUTION), metadata());
        webhookSender.failNextSends(ticketId, 2);

        eventPublisher.publish(opened);
        eventPublisher.publish(assigned);
        eventPublisher.publish(resolved);

        await().untilAsserted(() -> assertThat(webhookSender.attemptsFor(ticketId)).containsExactly(
            webhook(opened),
            webhook(opened),
            webhook(opened),
            webhook(assigned),
            webhook(resolved)
        ));
    }

    private static CustomerWebhook webhook(EventMessage<? extends TicketEvent> eventMessage) {
        return new CustomerWebhook(eventMessage.messageId(), CUSTOMER_ID, eventMessage.payload());
    }

    private static Metadata metadata() {
        return Metadata.builder().timestamp(TIMESTAMP).property("customerId", CUSTOMER_ID).build();
    }
}
