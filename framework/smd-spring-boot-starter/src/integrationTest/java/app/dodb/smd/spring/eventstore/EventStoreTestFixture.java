package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.sequence.EventSequenceStore;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.eventstore.storage.TokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.spring.eventstore.processing.EventStoreProcessingTestConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.fixed;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;
import static org.springframework.boot.WebApplicationType.NONE;

final class EventStoreTestFixture implements AutoCloseable {

    static final String SCHEDULING_DISABLED = "smd.event-store.scheduling.enabled=false";

    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final Duration POLLING_DELAY = ofMillis(50);

    private final ConfigurableApplicationContext context;

    private EventStoreTestFixture(ConfigurableApplicationContext context) {
        this.context = requireNonNull(context);
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

    EventStore createEventStore(ScheduledExecutorService scheduler) {
        return createEventStore(defaultProcessingConfig(), scheduler);
    }

    EventStore createEventStore(ProcessingConfig processingConfig, ScheduledExecutorService scheduler) {
        return new EventStore(EventStoreConfig.withoutDefaults()
            .transactionProvider(bean(TransactionProvider.class))
            .eventStorage(bean(EventStorage.class))
            .eventSerializer(bean(EventSerializer.class))
            .tokenStore(bean(TokenStore.class))
            .eventSequenceStore(bean(EventSequenceStore.class))
            .schedulingConfig(EventStoreConfig.SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(scheduler)
                .initialDelay(ZERO)
                .pollingDelay(POLLING_DELAY)
                .build())
            .processingConfig(processingConfig)
            .build());
    }

    @SafeVarargs
    final void storeEvents(SequencedEvent<? extends Event>... sequencedEvents) {
        long highestGlobalPosition = 0L;
        for (var sequencedEvent : sequencedEvents) {
            storeEvent(sequencedEvent);
            highestGlobalPosition = Math.max(highestGlobalPosition, sequencedEvent.globalPosition());
        }
        advanceGlobalPositionTo(highestGlobalPosition);
    }

    private <E extends Event> void storeEvent(SequencedEvent<E> sequencedEvent) {
        var serializedEvent = bean(EventSerializer.class).serialize(sequencedEvent.eventMessage());

        try (var connection = bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO smd_event_store
                     (event_id, aggregate_id, event_type, payload, metadata, occurred_at, global_position)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, serializedEvent.messageId().value());
            statement.setString(2, serializedEvent.subjectId().orElse(null));
            statement.setString(3, serializedEvent.eventType());
            statement.setBytes(4, serializedEvent.serializedPayload());
            statement.setBytes(5, serializedEvent.serializedMetadata());
            statement.setTimestamp(6, Timestamp.from(serializedEvent.createdAt()));
            statement.setLong(7, sequencedEvent.globalPosition());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store event at global position " + sequencedEvent.globalPosition(), e);
        }
    }

    record SequencedEvent<E extends Event>(long globalPosition, EventMessage<E> eventMessage) {

        SequencedEvent(long globalPosition, E event) {
            this(globalPosition, EventMessage.from(event, new Metadata(null, Instant.now(), null)));
        }
    }

    void advanceGlobalPositionTo(long globalPosition) {
        try (var connection = bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("SELECT setval('smd_event_store_global_position_seq', ?, true)")) {
            statement.setLong(1, globalPosition);
            statement.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to advance the event store global position to " + globalPosition, e);
        }
    }

    List<StoredEvent> storedEvents() {
        try (var connection = bean(DataSource.class).getConnection();
             var statement = connection.prepareStatement("""
                 SELECT global_position, event_id, aggregate_id, event_type, payload, metadata, occurred_at
                 FROM smd_event_store
                 ORDER BY global_position
                 """);
             var resultSet = statement.executeQuery()) {
            var events = new ArrayList<StoredEvent>();
            while (resultSet.next()) {
                events.add(new StoredEvent(
                    resultSet.getLong("global_position"),
                    new MessageId((UUID) resultSet.getObject("event_id")),
                    Optional.ofNullable(resultSet.getString("aggregate_id")),
                    resultSet.getString("event_type"),
                    resultSet.getBytes("payload"),
                    resultSet.getBytes("metadata"),
                    resultSet.getTimestamp("occurred_at").toInstant()
                ));
            }
            return List.copyOf(events);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read stored events", e);
        }
    }

    <E extends Event> EventMessage<E> deserialize(StoredEvent storedEvent) {
        return bean(EventSerializer.class).deserialize(new SerializedEvent(storedEvent.eventId(), storedEvent.globalPosition(), storedEvent.aggregateId(),
            storedEvent.eventType(), storedEvent.payload(), storedEvent.metadata(), storedEvent.occurredAt()));
    }

    Optional<TokenState> tokenState(String processingGroup) {
        return bean(TokenStore.class).tokenState(processingGroup);
    }

    Optional<EventSequenceState> eventSequenceState(String processingGroup, String aggregateId) {
        return bean(EventSequenceStore.class).eventSequenceState(processingGroup, aggregateId);
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
