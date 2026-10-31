package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.EventTypeResolver;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.serialization.StaticEventTypeResolver;
import app.dodb.smd.serialization.jackson.EventUpcaster;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUpcasterIntegrationTest {

    @Test
    void poll_eventStoredUnderOldTypeName_deliversCurrentEvent() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture()
            .configuration(TicketUpcasterConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start();
             var eventStore = fixture.createEventStore(scheduler)) {
            var stored = storeLegacyEvent(fixture, "ticket.opened.v1", """
                {"ticketId": "ticket-1"}
                """);
            var received = new CopyOnWriteArrayList<EventMessage<?>>();

            try (var _ = eventStore.subscribe(new EventSubscriberStub("ticket-view", received::add))) {
                // When
                scheduler.poll();

                // Then
                assertThat(received).singleElement().satisfies(message -> {
                    assertThat(message.messageId()).isEqualTo(stored.messageId());
                    assertThat(message.subject()).contains(new Subject("ticket", "ticket-1"));
                    assertThat(message.payload()).isEqualTo(new TicketOpenedEvent("ticket-1", "NORMAL"));
                });
            }
        }
    }

    @Test
    void start_withUpcasterProducingUnresolvedEventType_failsStartup() {
        // Given
        var configuration = eventStoreTestFixture()
            .configuration(UnresolvedUpcasterConfiguration.class)
            .properties(SCHEDULING_DISABLED);

        // When / Then
        assertThatThrownBy(configuration::start)
            .rootCause()
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("EventUpcaster['ticket.opened.v1' -> 'ticket.opened.v3'] produces event type 'ticket.opened.v3', "
                + "which has no upcaster and can not be resolved");
    }

    private static SerializedEvent storeLegacyEvent(EventStoreTestFixture fixture, String eventType, String payload) {
        var current = fixture.bean(EventSerializer.class).serialize(EventMessage.from(
            new TicketOpenedEvent("ticket-1", "HIGH"),
            Metadata.at(Instant.now())
        ));

        try (var connection = fixture.bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO smd_event_store (event_id, subject_type, subject_id, event_type, payload, metadata, occurred_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, current.messageId().value());
            statement.setString(2, current.subject().orElseThrow().type());
            statement.setString(3, current.subject().orElseThrow().id());
            statement.setString(4, eventType);
            statement.setBytes(5, payload.getBytes(UTF_8));
            statement.setBytes(6, current.serializedMetadata());
            statement.setTimestamp(7, Timestamp.from(current.createdAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store legacy event " + eventType, e);
        }
        return current;
    }

    record TicketOpenedEvent(@SubjectId("ticket") String ticketId, String priority) implements Event {
    }

    @Configuration
    static class TicketUpcasterConfiguration {

        @Bean
        EventTypeResolver eventTypeResolver() {
            return new StaticEventTypeResolver(Map.of("ticket.opened.v2", TicketOpenedEvent.class));
        }

        @Bean
        EventUpcaster ticketOpenedV1Upcaster() {
            return EventUpcaster.transform("ticket.opened.v1", "ticket.opened.v2", payload -> payload.put("priority", "NORMAL"));
        }
    }

    @Configuration
    static class UnresolvedUpcasterConfiguration {

        @Bean
        EventTypeResolver eventTypeResolver() {
            return new StaticEventTypeResolver(Map.of("ticket.opened.v2", TicketOpenedEvent.class));
        }

        @Bean
        EventUpcaster ticketOpenedV1Upcaster() {
            return EventUpcaster.rename("ticket.opened.v1", "ticket.opened.v3");
        }
    }
}
