package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.channel.EventStore;
import app.dodb.smd.eventstore.channel.EventStoreConfig;
import app.dodb.smd.eventstore.channel.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.SerializedEvent;
import app.dodb.smd.eventstore.store.TokenState;
import app.dodb.smd.eventstore.store.TokenStore;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import app.dodb.smd.spring.eventstore.processing.EventStoreProcessingTestConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.springframework.boot.WebApplicationType.NONE;

final class EventStoreTestFixture implements AutoCloseable {

    static final String SCHEDULING_DISABLED = "smd.event-store.scheduling.enabled=false";

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final Duration POLLING_DELAY = ofMillis(50);

    private final ConfigurableApplicationContext context;

    private EventStoreTestFixture(ConfigurableApplicationContext context) {
        this.context = context;
    }

    static Builder eventStoreTestFixture() {
        return new Builder();
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    <T> Map<String, T> beansOfType(Class<T> type) {
        return context.getBeansOfType(type);
    }

    EventStore createEventStore() {
        return createEventStore(defaultProcessingConfig());
    }

    EventStore createEventStore(ProcessingConfig processingConfig) {
        return new EventStore(EventStoreConfig.withoutDefaults()
            .transactionProvider(bean(TransactionProvider.class))
            .interceptors(List.of())
            .eventStorage(bean(EventStorage.class))
            .eventSerializer(bean(EventSerializer.class))
            .tokenStore(bean(TokenStore.class))
            .eventSequenceStore(bean(EventSubjectSequenceStore.class))
            .schedulingConfig(EventStoreConfig.SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(Executors.newScheduledThreadPool(4))
                .initialDelay(ZERO)
                .pollingDelay(POLLING_DELAY)
                .build())
            .processingConfig(processingConfig)
            .build());
    }

    @SafeVarargs
    final void storeEvents(SequencedEvent<? extends Event>... sequencedEvents) {
        long highestSequenceNumber = 0L;
        for (var sequencedEvent : sequencedEvents) {
            storeEvent(sequencedEvent);
            highestSequenceNumber = Math.max(highestSequenceNumber, sequencedEvent.sequenceNumber());
        }
        advanceEventStoreSequenceTo(highestSequenceNumber);
    }

    private <E extends Event> void storeEvent(SequencedEvent<E> sequencedEvent) {
        var serializedEvent = bean(EventSerializer.class).serialize(sequencedEvent.eventMessage());

        try (var connection = bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO smd_event_store
                     (message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at, sequence_number)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, serializedEvent.messageId().value());
            statement.setString(2, serializedEvent.subjectId().orElse(null));
            statement.setString(3, serializedEvent.eventType());
            statement.setBytes(4, serializedEvent.serializedPayload());
            statement.setBytes(5, serializedEvent.serializedMetadata());
            statement.setTimestamp(6, Timestamp.from(serializedEvent.createdAt()));
            statement.setLong(7, sequencedEvent.sequenceNumber());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store event at sequence number " + sequencedEvent.sequenceNumber(), e);
        }
    }

    record SequencedEvent<E extends Event>(long sequenceNumber, EventMessage<E> eventMessage) {

        SequencedEvent(long sequenceNumber, E event) {
            this(sequenceNumber, EventMessage.from(event, new Metadata(null, Instant.now(), null)));
        }
    }

    void advanceEventStoreSequenceTo(long sequenceNumber) {
        try (var connection = bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("SELECT setval('smd_event_store_sequence_number_seq', ?, true)")) {
            statement.setLong(1, sequenceNumber);
            statement.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to advance the event store sequence to " + sequenceNumber, e);
        }
    }

    List<SerializedEvent> storedEvents() {
        try (var connection = bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("""
                 SELECT message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at, sequence_number
                 FROM smd_event_store
                 ORDER BY sequence_number
                 """);
             var resultSet = statement.executeQuery()) {
            var events = new ArrayList<SerializedEvent>();
            while (resultSet.next()) {
                events.add(new SerializedEvent(
                    new MessageId((UUID) resultSet.getObject("message_id")),
                    resultSet.getLong("sequence_number"),
                    Optional.ofNullable(resultSet.getString("subject_id")),
                    resultSet.getString("event_type"),
                    resultSet.getBytes("serialized_payload"),
                    resultSet.getBytes("serialized_metadata"),
                    resultSet.getTimestamp("created_at").toInstant()
                ));
            }
            return List.copyOf(events);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read stored events", e);
        }
    }

    Optional<TokenState> tokenState(String processingGroup) {
        return bean(TokenStore.class).tokenState(processingGroup);
    }

    Optional<EventSequenceState> eventSequenceState(String processingGroup, String subjectId) {
        return bean(EventSubjectSequenceStore.class).eventSequenceState(processingGroup, subjectId);
    }

    Optional<EventSequenceState> globalEventSequenceState(String processingGroup) {
        return bean(EventSubjectSequenceStore.class).globalEventSequenceState(processingGroup);
    }

    @Override
    public void close() {
        context.close();
    }

    private ProcessingConfig defaultProcessingConfig() {
        return ProcessingConfig.withoutDefaults()
            .maxRetries(1)
            .batchSize(DEFAULT_BATCH_SIZE)
            .retryBackoffStrategy(fixed(ZERO))
            .gapTimeout(ofSeconds(1))
            .build();
    }

    static final class Builder {

        private final List<Class<?>> configurations = new ArrayList<>(
            List.of(EventStoreProcessingTestConfiguration.class)
        );
        private final List<String> properties = new ArrayList<>();

        Builder configuration(Class<?> configuration) {
            configurations.add(configuration);
            return this;
        }

        Builder properties(String... properties) {
            this.properties.addAll(List.of(properties));
            return this;
        }

        EventStoreTestFixture start() {
            var context = new SpringApplicationBuilder(configurations.toArray(Class<?>[]::new))
                .profiles("event-store-processing")
                .web(NONE)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
            return new EventStoreTestFixture(context);
        }
    }

}
