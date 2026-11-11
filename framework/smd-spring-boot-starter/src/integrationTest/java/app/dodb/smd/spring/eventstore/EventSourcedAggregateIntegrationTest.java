package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.aggregate.AggregateNotFoundException;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.spring.eventstore.aggregate.AggregateTestConfiguration;
import app.dodb.smd.spring.eventstore.aggregate.AuditLog;
import app.dodb.smd.spring.eventstore.aggregate.BankAccountOpenedEvent;
import app.dodb.smd.spring.eventstore.aggregate.DepositMoneyCommand;
import app.dodb.smd.spring.eventstore.aggregate.MoneyDepositedEvent;
import app.dodb.smd.spring.eventstore.aggregate.OpenAuditedAccountCommand;
import app.dodb.smd.spring.eventstore.aggregate.OpenBankAccountCommand;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.util.concurrent.Future.State.FAILED;
import static java.util.concurrent.Future.State.SUCCESS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class EventSourcedAggregateIntegrationTest {

    private static final String BANK_ACCOUNT = "bank-account";
    private static final UUID ACCOUNT_ID = UUID.fromString("8f1d2c3b-4a59-4e6f-8071-92a3b4c5d6e7");

    @Test
    void send_withAggregateCommands_storesVersionedStreamAndReplaysIt() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
            commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));

            // When
            long balance = commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 2));

            // Then
            assertThat(balance).isEqualTo(42L);
            assertThat(fixture.storedEvents())
                .extracting(StoredEvent::subject, StoredEvent::subjectVersion, stored -> fixture.deserialize(stored).payload())
                .containsExactly(
                    tuple(Optional.of(new Subject(BANK_ACCOUNT, ACCOUNT_ID.toString())), Optional.of(1L), new BankAccountOpenedEvent(ACCOUNT_ID)),
                    tuple(Optional.of(new Subject(BANK_ACCOUNT, ACCOUNT_ID.toString())), Optional.of(2L), new MoneyDepositedEvent(ACCOUNT_ID, 40)),
                    tuple(Optional.of(new Subject(BANK_ACCOUNT, ACCOUNT_ID.toString())), Optional.of(3L), new MoneyDepositedEvent(ACCOUNT_ID, 2))
                );
        }
    }

    @Test
    void send_withDepositAboveLimit_rejectsWithoutStoringIt() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));

            // When / Then
            assertThatThrownBy(() -> commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Deposit exceeds limit of 100: 101");

            assertThat(fixture.storedEvents())
                .extracting(stored -> fixture.deserialize(stored).payload())
                .containsExactly(new BankAccountOpenedEvent(ACCOUNT_ID));
        }
    }

    @Test
    void send_withCollaboratorWithoutBean_failsWithItsType() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);

            // When / Then
            assertThatThrownBy(() -> commandGateway.send(new OpenAuditedAccountCommand(ACCOUNT_ID)))
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(AuditLog.class.getName());

            assertThat(fixture.storedEvents()).isEmpty();
        }
    }

    @Test
    void send_withConcurrentCommandsOnSameVersion_storesOneAndRejectsTheOther() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            var transactions = fixture.bean(TransactionProvider.class);
            commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
            var bothLoaded = new CountDownLatch(2);

            // When
            List<Future<Long>> deposits;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                deposits = Stream.of(10L, 20L)
                    .map(amount -> executor.submit(() -> transactions.doInTransaction(() -> {
                        var balance = commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, amount));
                        awaitBothLoaded(bothLoaded);
                        return balance;
                    })))
                    .toList();
            }

            // Then
            assertThat(deposits).extracting(Future::state).containsExactlyInAnyOrder(SUCCESS, FAILED);
            assertThat(deposits).filteredOn(deposit -> deposit.state() == FAILED).singleElement()
                .extracting(Future::exceptionNow)
                .isInstanceOfSatisfying(ConcurrentAggregateChangeException.class, exception -> {
                    assertThat(exception.aggregateType()).isEqualTo(BANK_ACCOUNT);
                    assertThat(exception.aggregateId()).isEqualTo(ACCOUNT_ID.toString());
                    assertThat(exception.expectedVersion()).isEqualTo(1L);
                });
            assertThat(fixture.storedEvents()).hasSize(2);
        }
    }

    @Test
    void send_withCreatingAndChangingCommandsInOneTransaction_storesVersionsOneAndTwo() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            var transactions = fixture.bean(TransactionProvider.class);

            // When
            transactions.doInTransaction(() -> {
                commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
                commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));
            });

            // Then
            assertThat(fixture.storedEvents())
                .extracting(StoredEvent::subjectVersion, stored -> fixture.deserialize(stored).payload())
                .containsExactly(
                    tuple(Optional.of(1L), new BankAccountOpenedEvent(ACCOUNT_ID)),
                    tuple(Optional.of(2L), new MoneyDepositedEvent(ACCOUNT_ID, 40))
                );
        }
    }

    @Test
    void send_withTwoChangingCommandsInOneTransaction_secondSeesFirstAndStoresVersionsTwoAndThree() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            var transactions = fixture.bean(TransactionProvider.class);
            commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));

            // When
            long balance = transactions.doInTransaction(() -> {
                commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));
                return commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 2));
            });

            // Then
            assertThat(balance).isEqualTo(42L);
            assertThat(fixture.storedEvents())
                .extracting(StoredEvent::subjectVersion, stored -> fixture.deserialize(stored).payload())
                .containsExactly(
                    tuple(Optional.of(1L), new BankAccountOpenedEvent(ACCOUNT_ID)),
                    tuple(Optional.of(2L), new MoneyDepositedEvent(ACCOUNT_ID, 40)),
                    tuple(Optional.of(3L), new MoneyDepositedEvent(ACCOUNT_ID, 2))
                );
        }
    }

    @Test
    void send_withTransactionRolledBackAfterCommands_storesNothing() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            var transactions = fixture.bean(TransactionProvider.class);
            var failure = new IllegalStateException("Transaction failed after the commands");

            // When
            assertThatThrownBy(() -> transactions.doInTransaction(() -> {
                commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
                commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));
                throw failure;
            })).isSameAs(failure);

            // Then
            assertThat(fixture.storedEvents()).isEmpty();
            assertThatThrownBy(() -> commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 2)))
                .isInstanceOf(AggregateNotFoundException.class);
        }
    }

    @Test
    void send_inNewTransactionForAggregateCreatedInOuterTransaction_throwsAggregateNotFound() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            var transactions = fixture.bean(TransactionProvider.class);

            // When
            transactions.doInTransaction(() -> {
                commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));

                assertThatThrownBy(() -> transactions.doInNewTransaction(() -> commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40))))
                    .isInstanceOf(AggregateNotFoundException.class);
            });

            // Then
            assertThat(fixture.storedEvents())
                .extracting(StoredEvent::subjectVersion, stored -> fixture.deserialize(stored).payload())
                .containsExactly(tuple(Optional.of(1L), new BankAccountOpenedEvent(ACCOUNT_ID)));
        }
    }

    @Test
    void poll_withAggregateEvents_deliversAggregateIdAsSubject() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture()
            .configuration(AggregateTestConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var commandGateway = fixture.bean(CommandGateway.class);
            commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
            commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));
            var subjects = new CopyOnWriteArrayList<Subject>();
            var subscriber = new EventSubscriberStub("bank-account-view", eventMessage ->
                subjects.add(eventMessage.subject().orElseThrow()));

            try (var eventStore = fixture.createEventStore(scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                // When
                scheduler.poll();

                // Then
                var account = new Subject(BANK_ACCOUNT, ACCOUNT_ID.toString());
                assertThat(subjects).containsExactly(account, account);
            }
        }
    }

    private static void awaitBothLoaded(CountDownLatch bothLoaded) {
        bothLoaded.countDown();
        try {
            assertThat(bothLoaded.await(5, SECONDS)).as("both commands loaded the aggregate").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for both commands to load the aggregate", e);
        }
    }
}
