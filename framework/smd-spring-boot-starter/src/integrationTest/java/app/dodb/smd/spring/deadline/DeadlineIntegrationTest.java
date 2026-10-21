package app.dodb.smd.spring.deadline;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.eventstore.deadline.DeadlineStorage;
import app.dodb.smd.eventstore.deadline.DeadlineStore;
import app.dodb.smd.eventstore.deadline.DeadlineStoreConfig;
import app.dodb.smd.eventstore.serialization.DeadlineSerializer;
import app.dodb.smd.spring.deadline.ReminderHandler.Attempt;
import app.dodb.smd.spring.deadline.ReminderHandler.RemindCustomerCommand;
import app.dodb.smd.spring.deadline.ReminderHandler.ScheduleReminderCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.ABANDONED;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@ActiveProfiles("deadlines")
@SpringBootTest(classes = DeadlineTestConfiguration.class, webEnvironment = NONE)
class DeadlineIntegrationTest {

    private static final Duration TIMEOUT = ofSeconds(10);

    @Autowired
    private CommandGateway commandGateway;

    @Autowired
    private DeadlineScheduler deadlineScheduler;

    @Autowired
    private DeadlineStore deadlineStore;

    @Autowired
    private TransactionProvider transactionProvider;

    @Autowired
    private ReminderHandler reminders;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ticket_reminder (message_id UUID NOT NULL, ticket_id VARCHAR(255) NOT NULL)");
        jdbc.execute("DELETE FROM smd_deadline_store");
        jdbc.execute("DELETE FROM ticket_reminder");
        reminders.reset();
    }

    @AfterEach
    void releaseBlockedReminder() {
        reminders.releaseBlockedAttempt();
    }

    @Test
    void deadlineScheduler_isTheAutoconfiguredDeadlineStore() {
        assertThat(deadlineScheduler).isSameAs(deadlineStore);
    }

    @Test
    void schedule_fromCommandHandler_firesCommandOnceWithDeadlineIdAndSchedulingMetadata() {
        // Given
        var scheduleMetadata = new Metadata(null, null, null, Map.of("tenantId", "acme"));
        var scheduleCommand = new ScheduleReminderCommand("ticket-1", ZERO);

        // When
        DeadlineId deadlineId = commandGateway.send(scheduleCommand, scheduleMetadata);

        // Then
        await().atMost(TIMEOUT).until(() -> reminders.attempts().size() == 1);
        var attempt = reminders.attempts().getFirst();
        assertThat(attempt.messageId()).isEqualTo(new MessageId(deadlineId.value()));
        assertThat(attempt.ticketId()).isEqualTo("ticket-1");
        assertThat(attempt.metadata().parentMessageId()).isNotNull();
        assertThat(attempt.metadata().properties())
            .containsEntry("tenantId", "acme")
            .containsEntry(DeadlineStore.ERROR_COUNT, "0")
            .containsKey(DeadlineStore.DUE_AT);
        await().atMost(TIMEOUT).until(() -> deadlineStore.deadlineState(deadlineId).isEmpty());
        assertThat(storedReminders()).containsExactly(deadlineId.value());
    }

    @Test
    void schedule_whenSendingTransactionRollsBack_storesNothing() {
        // Given
        var failure = new IllegalStateException("Request failed after scheduling");
        var deadlineIds = new ArrayList<DeadlineId>();

        // When
        assertThatThrownBy(() -> transactionProvider.doInTransaction(() -> {
            deadlineIds.add(commandGateway.send(new ScheduleReminderCommand("ticket-1", ofHours(1))));
            throw failure;
        })).isSameAs(failure);

        // Then
        assertThat(deadlineIds).hasSize(1);
        assertThat(deadlineStore.deadlineState(deadlineIds.getFirst())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM smd_deadline_store", Integer.class)).isZero();
    }

    @Test
    void cancel_beforeDue_removesDeadline() {
        // Given
        var deadlineId = deadlineScheduler.schedule(new RemindCustomerCommand("ticket-1"), ofHours(1));

        // When
        var cancelled = deadlineScheduler.cancel(deadlineId);

        // Then
        assertThat(cancelled).isTrue();
        assertThat(deadlineStore.deadlineState(deadlineId)).isEmpty();
        assertThat(deadlineScheduler.cancel(deadlineId)).isFalse();
    }

    @Test
    void fire_whenHandlerFails_rollsBackItsWritesAndRetriesSameMessage() {
        // Given
        reminders.failNextAttempts(1);

        // When
        var deadlineId = deadlineScheduler.schedule(new RemindCustomerCommand("ticket-1"), ZERO);

        // Then
        await().atMost(TIMEOUT).until(() -> deadlineStore.deadlineState(deadlineId).isEmpty() && reminders.attempts().size() == 2);
        assertThat(reminders.attempts()).extracting(Attempt::messageId).containsOnly(new MessageId(deadlineId.value()));
        assertThat(reminders.attempts()).extracting(attempt -> attempt.metadata().properties().get(DeadlineStore.ERROR_COUNT))
            .containsExactly("0", "1");
        assertThat(storedReminders()).containsExactly(deadlineId.value());
    }

    @Test
    void fire_whenRetriesAreExhausted_abandonsDeadlineAndKeepsNoHandlerWrites() {
        // Given
        reminders.failNextAttempts(10);

        // When
        var deadlineId = deadlineScheduler.schedule(new RemindCustomerCommand("ticket-1"), ZERO);

        // Then
        await().atMost(TIMEOUT).until(() -> deadlineStore.deadlineState(deadlineId)
            .filter(state -> state.status() == ABANDONED)
            .isPresent());
        assertThat(reminders.attempts()).hasSize(2);
        assertThat(deadlineStore.deadlineState(deadlineId)).hasValueSatisfying(state ->
            assertThat(state.lastErrorMessage()).hasValueSatisfying(message -> assertThat(message).contains("Simulated reminder failure")));
        assertThat(storedReminders()).isEmpty();
    }

    @Test
    void fire_withTwoStoresPolling_firesEachDeadlineOnce() {
        // Given
        try (var secondNode = new DeadlineStore(secondNodeConfig())) {
            secondNode.start(commandGateway);

            // When
            var deadlineIds = new ArrayList<UUID>();
            for (int i = 0; i < 30; i++) {
                deadlineIds.add(deadlineScheduler.schedule(new RemindCustomerCommand("ticket-" + i), ZERO).value());
            }

            // Then
            await().atMost(TIMEOUT).until(() -> storedReminders().size() == deadlineIds.size()
                && jdbc.queryForObject("SELECT COUNT(*) FROM smd_deadline_store", Integer.class) == 0);
            assertThat(storedReminders()).containsExactlyInAnyOrderElementsOf(deadlineIds);
            assertThat(reminders.attempts()).hasSize(deadlineIds.size());
        }
    }

    @Test
    void cancel_whileDeadlineFires_waitsForFireAndReturnsFalse() throws Exception {
        // Given
        reminders.blockNextAttempt();
        var deadlineId = deadlineScheduler.schedule(new RemindCustomerCommand("ticket-1"), ZERO);
        reminders.awaitBlockedAttempt();

        // When
        var cancelled = CompletableFuture.supplyAsync(() -> deadlineScheduler.cancel(deadlineId));
        await().atMost(TIMEOUT).until(this::cancelIsWaitingForRowLock);
        reminders.releaseBlockedAttempt();

        // Then
        assertThat(cancelled.get(TIMEOUT.toSeconds(), SECONDS)).isFalse();
        assertThat(storedReminders()).containsExactly(deadlineId.value());
        assertThat(deadlineStore.deadlineState(deadlineId)).isEmpty();
    }

    private boolean cancelIsWaitingForRowLock() {
        return jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query LIKE 'DELETE FROM smd_deadline_store%'
            """, Integer.class) == 1;
    }

    private List<UUID> storedReminders() {
        return jdbc.queryForList("SELECT message_id FROM ticket_reminder", UUID.class);
    }

    private DeadlineStoreConfig secondNodeConfig() {
        return DeadlineStoreConfig.withoutDefaults()
            .transactionProvider(transactionProvider)
            .principalProvider(context(PrincipalProvider.class))
            .timeProvider(context(TimeProvider.class))
            .deadlineStorage(context(DeadlineStorage.class))
            .deadlineSerializer(context(DeadlineSerializer.class))
            .scheduler(newSingleThreadScheduledExecutor())
            .initialDelay(ZERO)
            .pollingDelay(ofMillis(50))
            .batchSize(10)
            .maxRetries(1)
            .retryBackoffStrategy(fixed(ZERO))
            .build();
    }

    private <T> T context(Class<T> type) {
        return applicationContext.getBean(type);
    }
}
