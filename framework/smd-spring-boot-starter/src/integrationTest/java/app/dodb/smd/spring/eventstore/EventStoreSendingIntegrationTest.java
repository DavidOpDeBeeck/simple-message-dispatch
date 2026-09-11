package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStoreSendingIntegrationTest {

    @Test
    void send_withoutExistingTransaction_commitsBeforeReturning() {
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var eventStore = fixture.bean(EventStore.class);
            var eventSerializer = fixture.bean(EventSerializer.class);
            var message = EventMessage.from(new TestEventWithSubjectId("direct-send"),
                new Metadata(null, Instant.parse("2026-04-22T10:15:30Z"), null));

            eventStore.send(message);

            assertThat(fixture.storedEvents()).singleElement().satisfies(stored ->
                assertThat(eventSerializer.deserialize(stored)).isEqualTo(message));
        }
    }

    @Test
    void send_withExistingTransaction_defersStorageUntilTransactionCompletes() {
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var eventStore = fixture.bean(EventStore.class);
            var transactions = fixture.bean(TransactionProvider.class);
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            var message = EventMessage.from(new TestEventWithSubjectId("deferred-send"),
                new Metadata(null, Instant.parse("2026-04-22T10:15:30Z"), null));

            transactions.doInTransaction(() -> {
                eventStore.send(message);

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM smd_event_store", Integer.class)).isZero();
            });

            assertThat(fixture.storedEvents()).singleElement().satisfies(stored ->
                assertThat(fixture.bean(EventSerializer.class).deserialize(stored)).isEqualTo(message));
        }
    }

    @Test
    void send_whenLaterDeferredWorkFails_rollsBackStoredEventAndPreservesFailure() {
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var eventStore = fixture.bean(EventStore.class);
            var transactions = fixture.bean(TransactionProvider.class);
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            var failure = new IllegalStateException("Deferred work failed after storing the event");
            var message = EventMessage.from(new TestEventWithSubjectId("rolled-back-send"),
                new Metadata(null, Instant.parse("2026-04-22T10:15:30Z"), null));

            assertThatThrownBy(() -> transactions.doInTransaction(() -> {
                eventStore.send(message);
                transactions.defer(() -> {
                    assertThat(jdbc.queryForList("SELECT message_id FROM smd_event_store", UUID.class))
                        .containsExactly(message.messageId().value());
                    throw failure;
                });
            })).isSameAs(failure);

            assertThat(fixture.storedEvents()).isEmpty();
        }
    }
}
