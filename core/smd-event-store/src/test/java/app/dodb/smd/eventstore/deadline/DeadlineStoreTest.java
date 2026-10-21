package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.eventstore.serialization.JacksonDeadlineSerializer;
import app.dodb.smd.eventstore.serialization.JacksonDeadlineSerializer.DeadlineSerializationException;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.serialization.StaticCommandTypeResolver;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryDeadlineStorage;
import app.dodb.smd.test.NoOpTransactionProvider;
import app.dodb.smd.test.TimeProviderStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.ABANDONED;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.FAILED;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.PENDING;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");
    private static final SimplePrincipal PRINCIPAL = new SimplePrincipal(UUID.fromString("d9aa7511-e40c-4ef0-bc50-abef81f7d00f"));
    private static final Duration BACKOFF = ofMinutes(1);

    private final TimeProviderStub time = new TimeProviderStub(new SystemTimeProvider());
    private final RecordingCommandGateway commands = new RecordingCommandGateway();
    private final DeadlineStore deadlines = deadlineStore(2);

    DeadlineStoreTest() {
        time.stubTime(NOW);
    }

    @AfterEach
    void closeStore() {
        deadlines.close();
    }

    @Test
    void schedule_withDelay_storesPendingDeadlineDueAfterDelay() {
        var deadlineId = deadlines.schedule(new RemindCustomerCommand("ticket-1"), ofHours(4));

        assertThat(deadlines.deadlineState(deadlineId)).hasValueSatisfying(state -> {
            assertThat(state.commandType()).isEqualTo(RemindCustomerCommand.class.getName());
            assertThat(state.dueAt()).isEqualTo(NOW.plus(ofHours(4)));
            assertThat(state.nextAttemptAt()).isEqualTo(NOW.plus(ofHours(4)));
            assertThat(state.status()).isEqualTo(PENDING);
            assertThat(state.errorCount()).isZero();
        });
    }

    @Test
    void schedule_withNegativeDelay_throwsWithCommandAndDelay() {
        assertThatThrownBy(() -> deadlines.schedule(new RemindCustomerCommand("ticket-1"), ofHours(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Deadline delay must not be negative: command=" + RemindCustomerCommand.class.getName() + ", delay=PT-1H");
    }

    @Test
    void schedule_withUnmappedCommandType_failsBeforeStoring() {
        var storage = new InMemoryDeadlineStorage();
        var serializer = new JacksonDeadlineSerializer(JsonMapper.builder().build(), new StaticCommandTypeResolver(Map.of()));
        try (var store = new DeadlineStore(config(storage, 0).deadlineSerializer(serializer).build())) {

            assertThatThrownBy(() -> store.schedule(new RemindCustomerCommand("ticket-1"), NOW))
                .isInstanceOf(DeadlineSerializationException.class)
                .hasMessageStartingWith("Failed to resolve command type for deadline");
            assertThat(storage.claimDue(NOW)).isEmpty();
        }
    }

    @Test
    void fireDueDeadlines_beforeDueTime_sendsNothing() {
        deadlines.schedule(new RemindCustomerCommand("ticket-1"), ofHours(4));
        time.stubTime(NOW.plus(ofHours(4)).minusMillis(1));

        var attempts = deadlines.fireDueDeadlines(commands);

        assertThat(attempts).isZero();
        assertThat(commands.messages).isEmpty();
    }

    @Test
    void fireDueDeadlines_whenDue_sendsCommandWithDeadlineIdAsMessageIdAndRemovesDeadline() {
        var deadlineId = deadlines.schedule(new RemindCustomerCommand("ticket-1"), ofHours(4));
        time.stubTime(NOW.plus(ofHours(4)));

        deadlines.fireDueDeadlines(commands);

        assertThat(commands.messages).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo(new MessageId(deadlineId.value()));
            assertThat(message.payload()).isEqualTo(new RemindCustomerCommand("ticket-1"));
        });
        assertThat(deadlines.deadlineState(deadlineId)).isEmpty();
        assertThat(deadlines.cancel(deadlineId)).isFalse();
    }

    @Test
    void fireDueDeadlines_whenDue_restoresSchedulingMetadataWithAttemptProperties() {
        var scheduledAt = NOW.minus(ofHours(1));
        var handledMessage = CommandMessage.from(new OpenTicketCommand("ticket-1"),
            new Metadata(PRINCIPAL, scheduledAt, null, Map.of("tenantId", "acme")));
        var dueAt = NOW.plus(ofHours(4));
        MetadataFactory.runInScope(handledMessage, () -> deadlines.schedule(new RemindCustomerCommand("ticket-1"), dueAt));
        var firedAt = dueAt.plusSeconds(3);
        time.stubTime(firedAt);

        deadlines.fireDueDeadlines(commands);

        assertThat(commands.messages).singleElement().extracting(CommandMessage::metadata).satisfies(metadata -> {
            assertThat(metadata.principal()).isEqualTo(PRINCIPAL);
            assertThat(metadata.parentMessageId()).isEqualTo(handledMessage.messageId());
            assertThat(metadata.timestamp()).isEqualTo(firedAt);
            assertThat(metadata.properties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tenantId", "acme",
                DeadlineStore.DUE_AT, dueAt.toString(),
                DeadlineStore.ERROR_COUNT, "0"
            ));
        });
    }

    @Test
    void fireDueDeadlines_withSeveralDueDeadlines_sendsInDueOrder() {
        deadlines.schedule(new RemindCustomerCommand("second"), NOW.plusSeconds(2));
        deadlines.schedule(new RemindCustomerCommand("first"), NOW.plusSeconds(1));
        deadlines.schedule(new RemindCustomerCommand("later"), NOW.plusSeconds(10));
        time.stubTime(NOW.plusSeconds(2));

        deadlines.fireDueDeadlines(commands);

        assertThat(commands.payloads()).containsExactly(new RemindCustomerCommand("first"), new RemindCustomerCommand("second"));
    }

    @Test
    void fireDueDeadlines_withMoreDueDeadlinesThanBatchSize_attemptsOnlyBatchSize() {
        try (var store = new DeadlineStore(config(new InMemoryDeadlineStorage(), 0).batchSize(2).build())) {
            store.schedule(new RemindCustomerCommand("first"), NOW);
            store.schedule(new RemindCustomerCommand("second"), NOW);
            store.schedule(new RemindCustomerCommand("third"), NOW);

            var attempts = store.fireDueDeadlines(commands);

            assertThat(attempts).isEqualTo(2);
            assertThat(commands.messages).hasSize(2);
        }
    }

    @Test
    void cancel_pendingDeadline_returnsTrueAndPreventsFiring() {
        var deadlineId = deadlines.schedule(new RemindCustomerCommand("ticket-1"), NOW);

        var cancelled = deadlines.cancel(deadlineId);
        deadlines.fireDueDeadlines(commands);

        assertThat(cancelled).isTrue();
        assertThat(commands.messages).isEmpty();
        assertThat(deadlines.deadlineState(deadlineId)).isEmpty();
    }

    @Test
    void cancel_unknownDeadline_returnsFalse() {
        assertThat(deadlines.cancel(DeadlineId.generate())).isFalse();
    }

    @Test
    void fireDueDeadlines_whenCommandFails_recordsFailureAndRetriesSameMessageAfterBackoff() {
        var failure = new IllegalStateException("Mail server unavailable");
        commands.failNext(failure);
        var deadlineId = deadlines.schedule(new RemindCustomerCommand("ticket-1"), NOW);

        deadlines.fireDueDeadlines(commands);

        assertThat(deadlines.deadlineState(deadlineId)).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(FAILED);
            assertThat(state.errorCount()).isEqualTo(1);
            assertThat(state.nextAttemptAt()).isEqualTo(NOW.plus(BACKOFF));
            assertThat(state.lastErrorAt()).contains(NOW);
            assertThat(state.lastErrorMessage()).hasValueSatisfying(message -> assertThat(message).contains("Mail server unavailable"));
        });

        time.stubTime(NOW.plus(BACKOFF).minusMillis(1));
        deadlines.fireDueDeadlines(commands);
        assertThat(commands.messages).hasSize(1);

        time.stubTime(NOW.plus(BACKOFF));
        deadlines.fireDueDeadlines(commands);

        assertThat(commands.messages).hasSize(2);
        assertThat(commands.messages.get(1).messageId()).isEqualTo(commands.messages.get(0).messageId());
        assertThat(commands.messages.get(1).metadata().properties()).containsEntry(DeadlineStore.ERROR_COUNT, "1");
        assertThat(deadlines.deadlineState(deadlineId)).isEmpty();
    }

    @Test
    void fireDueDeadlines_whenRetriesAreExhausted_abandonsDeadline() {
        try (var store = new DeadlineStore(config(new InMemoryDeadlineStorage(), 1).retryBackoffStrategy(fixed(Duration.ZERO)).build())) {
            commands.failNext(new IllegalStateException("first"), new IllegalStateException("second"));
            var deadlineId = store.schedule(new RemindCustomerCommand("ticket-1"), NOW);

            store.fireDueDeadlines(commands);
            store.fireDueDeadlines(commands);

            assertThat(commands.messages).hasSize(2);
            assertThat(store.deadlineState(deadlineId)).hasValueSatisfying(state -> {
                assertThat(state.status()).isEqualTo(ABANDONED);
                assertThat(state.lastErrorMessage()).hasValueSatisfying(message -> assertThat(message).contains("second"));
            });
            assertThat(store.cancel(deadlineId)).isFalse();
        }
    }

    @Test
    void start_whenAlreadyStarted_throws() {
        deadlines.start(commands);

        assertThatThrownBy(() -> deadlines.start(commands))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Deadline store already started");
    }

    private DeadlineStore deadlineStore(int maxRetries) {
        return new DeadlineStore(config(new InMemoryDeadlineStorage(), maxRetries).build());
    }

    private DeadlineStoreConfig.Builder config(DeadlineStorage storage, int maxRetries) {
        var objectMapper = JsonMapper.builder().addModule(new SMDJacksonModule()).build();
        return DeadlineStoreConfig.withDefaults()
            .transactionProvider(new NoOpTransactionProvider())
            .principalProvider(() -> PRINCIPAL)
            .timeProvider(time)
            .deadlineStorage(storage)
            .deadlineSerializer(new JacksonDeadlineSerializer(objectMapper))
            .initialDelay(ofHours(1))
            .maxRetries(maxRetries)
            .retryBackoffStrategy(fixed(BACKOFF));
    }

    private record OpenTicketCommand(String ticketId) implements Command<Void> {
    }

    private record RemindCustomerCommand(String ticketId) implements Command<Void> {
    }

    private static class RecordingCommandGateway implements CommandGateway {

        private final List<CommandMessage<?, ?>> messages = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();

        void failNext(RuntimeException... failures) {
            this.failures.addAll(List.of(failures));
        }

        List<Object> payloads() {
            return messages.stream().<Object>map(CommandMessage::payload).toList();
        }

        @Override
        public <R, C extends Command<R>> R send(C command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, C extends Command<R>> R send(C command, Metadata metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage) {
            messages.add(commandMessage);
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            return null;
        }
    }
}
