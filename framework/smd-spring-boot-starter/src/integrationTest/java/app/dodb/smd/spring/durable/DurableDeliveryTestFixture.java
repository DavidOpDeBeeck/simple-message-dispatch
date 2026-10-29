package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.durable.DurableDelivery;
import app.dodb.smd.durable.DurableEventDispatcher;
import app.dodb.smd.durable.DurableEventDispatcherConfig;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.spring.durable.processing.DurableDeliveryTestConfiguration;
import app.dodb.smd.spring.durable.processing.NoteEvent;
import app.dodb.smd.spring.durable.processing.OrderEvent;
import app.dodb.smd.test.EventSubscriberStub;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.fixed;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofDays;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

final class DurableDeliveryTestFixture implements AutoCloseable {

    static final RetryBackoffStrategy NO_RETRY_DURING_TEST = fixed(ofDays(1));

    private static final int BATCH_SIZE = 10;
    private static final int LANE_COUNT = 16;
    private static final int MAX_POLLS = 20;
    private static final RowMapper<MessageId> MESSAGE_ID = (rs, _) -> new MessageId(rs.getObject("message_id", UUID.class));
    private static final RowMapper<PendingEvent> PENDING_EVENT = (rs, _) -> new PendingEvent(
        rs.getString("subscriber"),
        new MessageId(rs.getObject("message_id", UUID.class)));
    private static final RowMapper<FailedDelivery> FAILED_DELIVERY = (rs, _) -> new FailedDelivery(
        rs.getString("subscriber"),
        rs.getString("subject_id"),
        rs.getInt("attempts"),
        rs.getString("last_error"));

    private final ConfigurableApplicationContext context;
    private final JdbcTemplate jdbc;

    private DurableDeliveryTestFixture(ConfigurableApplicationContext context) {
        this.context = requireNonNull(context);
        this.jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    }

    static DurableDeliveryTestFixture durableDeliveryTestFixture() {
        var context = new SpringApplicationBuilder(DurableDeliveryTestConfiguration.class)
            .profiles("durable-delivery")
            .web(NONE)
            .run();
        var fixture = new DurableDeliveryTestFixture(context);
        fixture.jdbc.execute("CREATE TABLE handler_effect (id BIGSERIAL PRIMARY KEY, effect VARCHAR(255) NOT NULL)");
        return fixture;
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    DurableEventDispatcherConfig.Builder dispatcherConfig(ScheduledExecutorService scheduler) {
        return DurableEventDispatcherConfig.withoutDefaults()
            .dispatcherStorage(bean(DispatcherStorage.class))
            .transactionProvider(bean(TransactionProvider.class))
            .eventSerializer(bean(EventSerializer.class))
            .schedulingConfig(DurableEventDispatcherConfig.SchedulingConfig.withoutDefaults()
                .scheduler(scheduler)
                .idleBackoffStrategy(fixed(ZERO))
                .build())
            .processingConfig(processingConfig().build());
    }

    DurableEventDispatcherConfig.ProcessingConfig.Builder processingConfig() {
        return DurableEventDispatcherConfig.ProcessingConfig.withoutDefaults()
            .batchSize(BATCH_SIZE)
            .laneCount(LANE_COUNT)
            .retryBackoffStrategy(fixed(ZERO));
    }

    DurableEventDispatcher dispatcher(String name, ScheduledExecutorService scheduler) {
        return dispatcher(name, dispatcherConfig(scheduler).build());
    }

    DurableEventDispatcher dispatcherWithoutRetries(String name, ScheduledExecutorService scheduler) {
        return dispatcher(name, dispatcherConfig(scheduler)
            .processingConfig(processingConfig().retryBackoffStrategy(NO_RETRY_DURING_TEST).build())
            .build());
    }

    DurableEventDispatcher dispatcherWithBatchSize(String name, ScheduledExecutorService scheduler, int batchSize) {
        return dispatcher(name, dispatcherConfig(scheduler)
            .processingConfig(processingConfig().batchSize(batchSize).build())
            .build());
    }

    DurableEventDispatcher dispatcherWithOneLaneWithoutRetries(String name, ScheduledExecutorService scheduler) {
        return dispatcher(name, dispatcherConfig(scheduler)
            .processingConfig(processingConfig().laneCount(1).retryBackoffStrategy(NO_RETRY_DURING_TEST).build())
            .build());
    }

    DurableEventDispatcher dispatcherWithLaneCount(String name, ScheduledExecutorService scheduler, int laneCount) {
        return dispatcher(name, dispatcherConfig(scheduler)
            .processingConfig(processingConfig().laneCount(laneCount).build())
            .build());
    }

    DurableEventDispatcher dispatcher(String name, DurableEventDispatcherConfig config) {
        return new DurableDelivery(config).dispatcher(name);
    }

    void pollUntilIdle(ManualPollingScheduler scheduler) throws Exception {
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            var before = deliveryState();
            scheduler.poll();
            if (deliveryState().equals(before)) {
                return;
            }
        }
        throw new AssertionError("Pending events still changed after " + MAX_POLLS + " polls");
    }

    void publish(EventSink sink, EventMessage<?>... messages) {
        bean(TransactionProvider.class).doInTransaction(() -> {
            for (var message : messages) {
                sink.send(message);
            }
        });
    }

    List<MessageId> queuedEvents(String dispatcher) {
        return jdbc.query("SELECT message_id FROM smd_dispatcher_queue WHERE dispatcher = ? ORDER BY id", MESSAGE_ID, dispatcher);
    }

    List<String> subscribers(String dispatcher) {
        return jdbc.queryForList("SELECT subscriber FROM smd_dispatcher_subscriber WHERE dispatcher = ? ORDER BY subscriber", String.class, dispatcher);
    }

    List<PendingEvent> pendingEvents(String dispatcher) {
        return jdbc.query("""
                SELECT subscriber, message_id
                FROM smd_dispatcher_queue
                WHERE dispatcher = ?
                ORDER BY id, subscriber
                """,
            PENDING_EVENT, dispatcher);
    }

    List<FailedDelivery> failedDeliveries(String dispatcher) {
        return jdbc.query("""
                SELECT subscriber, subject_id, attempts, last_error
                FROM smd_dispatcher_queue
                WHERE dispatcher = ? AND attempts > 0
                ORDER BY subscriber, id
                """,
            FAILED_DELIVERY, dispatcher);
    }

    private List<Object> deliveryState() {
        var state = new ArrayList<Object>();
        state.addAll(jdbc.query("""
                SELECT subscriber, message_id
                FROM smd_dispatcher_queue
                ORDER BY dispatcher, id, subscriber
                """,
            PENDING_EVENT));
        state.addAll(jdbc.query("""
                SELECT subscriber, subject_id, attempts, last_error
                FROM smd_dispatcher_queue
                WHERE attempts > 0
                ORDER BY dispatcher, subscriber, id
                """,
            FAILED_DELIVERY));
        return state;
    }

    void recordHandlerEffect(Object effect) {
        jdbc.update("INSERT INTO handler_effect (effect) VALUES (?)", effect.toString());
    }

    List<String> handlerEffects() {
        return jdbc.queryForList("SELECT effect FROM handler_effect ORDER BY id", String.class);
    }

    void execute(String sql) {
        jdbc.execute(sql);
    }

    static List<Object> subscribe(DurableEventDispatcher dispatcher, String subscriber) {
        var handled = new CopyOnWriteArrayList<Object>();
        dispatcher.subscribe(new EventSubscriberStub(subscriber, message -> handled.add(message.payload())));
        return handled;
    }

    static EventMessage<OrderEvent> order(String orderId, int sequence) {
        return EventMessage.from(new OrderEvent(orderId, sequence), metadata());
    }

    static EventMessage<NoteEvent> note(String text) {
        return EventMessage.from(new NoteEvent(text), metadata());
    }

    static Metadata metadata() {
        return new Metadata(null, Instant.parse("2026-10-22T10:15:30Z"), null, Map.of("tenant", "acme"));
    }

    static void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(5, SECONDS)).as("released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for release", e);
        }
    }

    @Override
    public void close() {
        context.close();
    }

    record PendingEvent(String subscriber, MessageId messageId) {
    }

    record FailedDelivery(String subscriber, String subject, int attempts, String lastError) {
    }
}
