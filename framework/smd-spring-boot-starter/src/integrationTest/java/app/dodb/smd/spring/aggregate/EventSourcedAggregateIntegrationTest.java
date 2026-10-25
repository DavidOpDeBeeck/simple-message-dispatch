package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.serialization.EventSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;

import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

class EventSourcedAggregateIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("8f1d2c3b-4a59-4e6f-8071-92a3b4c5d6e7");

    private ConfigurableApplicationContext context;

    @BeforeEach
    void startContext() {
        context = new SpringApplicationBuilder(AggregateTestConfiguration.class)
            .profiles("event-store-processing")
            .web(NONE)
            .run();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void send_aggregateCommands_storesVersionedStreamAndReplaysIt() {
        var commandGateway = context.getBean(CommandGateway.class);
        commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
        commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));

        long balance = commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 2));

        assertThat(balance).isEqualTo(42L);
        assertThat(stream()).containsExactly(
            new BankAccountOpenedEvent(ACCOUNT_ID),
            new MoneyDepositedEvent(ACCOUNT_ID, 40),
            new MoneyDepositedEvent(ACCOUNT_ID, 2)
        );
        assertThat(new JdbcTemplate(context.getBean(DataSource.class)).queryForList("""
                SELECT aggregate_version
                FROM smd_event_store
                WHERE aggregate_type = 'bank-account' AND subject_id = ?
                ORDER BY sequence_number
                """, Long.class, ACCOUNT_ID.toString()))
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    void send_aggregateCommand_deliversEventsToDurableProcessingGroup() {
        var commandGateway = context.getBean(CommandGateway.class);
        commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));

        commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, 40));

        await().atMost(ofSeconds(5)).untilAsserted(() ->
            assertThat(context.getBean(BankAccountView.class).deposits()).containsExactly(ACCOUNT_ID + ":40"));
    }

    @Test
    void send_concurrentCommandsOnSameVersion_storesOneAndRejectsTheOther() throws Exception {
        var commandGateway = context.getBean(CommandGateway.class);
        var transactionProvider = context.getBean(TransactionProvider.class);
        commandGateway.send(new OpenBankAccountCommand(ACCOUNT_ID));
        var bothLoaded = new CyclicBarrier(2);

        List<Future<Long>> deposits;
        try (var executor = Executors.newFixedThreadPool(2)) {
            deposits = List.of(10L, 20L).stream()
                .map(amount -> executor.submit(() -> transactionProvider.doInTransaction(() -> {
                    Long balance = commandGateway.send(new DepositMoneyCommand(ACCOUNT_ID, amount));
                    awaitBoth(bothLoaded);
                    return balance;
                })))
                .toList();
        }

        var failures = deposits.stream().map(EventSourcedAggregateIntegrationTest::failure).filter(failure -> failure != null).toList();
        assertThat(failures).singleElement().isInstanceOfSatisfying(ConcurrentAggregateChangeException.class, exception -> {
            assertThat(exception.aggregateType()).isEqualTo("bank-account");
            assertThat(exception.aggregateId()).isEqualTo(ACCOUNT_ID.toString());
            assertThat(exception.expectedVersion()).isEqualTo(1L);
        });
        assertThat(stream()).hasSize(2);
    }

    private List<Object> stream() {
        var eventSerializer = context.getBean(EventSerializer.class);
        return context.getBean(EventStorage.class).loadAggregateStream("bank-account", ACCOUNT_ID.toString()).stream()
            .<Object>map(serializedEvent -> eventSerializer.deserialize(serializedEvent).payload())
            .toList();
    }

    private static void awaitBoth(CyclicBarrier barrier) {
        try {
            barrier.await(5, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent commands did not both load the aggregate", e);
        }
    }

    private static Throwable failure(Future<Long> future) {
        try {
            future.get(10, SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent command did not complete", e);
        }
    }
}
