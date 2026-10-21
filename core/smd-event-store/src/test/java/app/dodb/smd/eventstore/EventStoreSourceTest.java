package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.test.EventSubscriberStub;
import app.dodb.smd.test.NoOpTransactionProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class EventStoreSourceTest {

    private static final String PROCESSING_GROUP = "ticket-view";

    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final JacksonEventSerializer eventSerializer = new JacksonEventSerializer(JsonMapper.builder()
        .addModule(new SMDJacksonModule())
        .build());

    @Test
    void subscribe_withBacklogOfFullBatches_pollsAgainWithoutPollingDelay() {
        storeEvents(5);
        var received = new CopyOnWriteArrayList<String>();
        try (var eventStore = eventStore(newSingleThreadScheduledExecutor(), ZERO, ofHours(1));
             var _ = eventStore.outlet().subscribe(new EventSubscriberStub(PROCESSING_GROUP, message ->
                 received.add(message.metadata().properties().get("sequenceNumber"))))) {

            await().atMost(ofSeconds(5)).untilAsserted(() ->
                assertThat(received).containsExactly("1", "2", "3", "4", "5"));
        }
    }

    @Test
    void close_withPendingPoll_cancelsPollAndStopsSchedulerPromptly() {
        storeEvents(1);
        var received = new CopyOnWriteArrayList<String>();
        var scheduler = newSingleThreadScheduledExecutor();
        var eventStore = eventStore(scheduler, ofHours(1), ofHours(1));
        eventStore.outlet().subscribe(new EventSubscriberStub(PROCESSING_GROUP, message ->
            received.add(message.metadata().properties().get("sequenceNumber"))));

        assertTimeoutPreemptively(ofSeconds(5), eventStore::close);

        assertThat(scheduler.isTerminated()).isTrue();
        assertThat(received).isEmpty();
    }

    private EventStore eventStore(ScheduledExecutorService scheduler, Duration initialDelay, Duration pollingDelay) {
        return new EventStore(EventStoreConfig.withoutDefaults()
            .transactionProvider(new NoOpTransactionProvider())
            .eventStorage(eventStorage)
            .eventSerializer(eventSerializer)
            .tokenStore(new InMemoryTokenStore())
            .eventSequenceStore(new InMemoryEventSubjectSequenceStore())
            .schedulingConfig(SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(scheduler)
                .initialDelay(initialDelay)
                .pollingDelay(pollingDelay)
                .build())
            .processingConfig(ProcessingConfig.withDefaults()
                .batchSize(2)
                .retryBackoffStrategy(fixed(ZERO))
                .build())
            .build());
    }

    private void storeEvents(int count) {
        for (int i = 0; i < count; i++) {
            eventStorage.store(eventSerializer.serialize(EventMessage.from(new TicketChanged(), new Metadata(null, Instant.now(), null))));
        }
    }

    record TicketChanged() implements Event {
    }
}
