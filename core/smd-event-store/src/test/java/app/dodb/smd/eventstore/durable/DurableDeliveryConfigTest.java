package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableDeliveryConfigTest {

    @Test
    void build_withDefaults_usesSafeDefaults() {
        // When
        var config = complete().build();

        // Then
        assertThat(config.getPollingDelay()).isEqualTo(DurableDeliveryConfig.DEFAULT_POLLING_DELAY);
        assertThat(config.getStallWarningAfter()).isEqualTo(DurableDeliveryConfig.DEFAULT_STALL_WARNING_AFTER);
        assertThat(config.getRetryBackoffStrategy()).isEqualTo(DurableDeliveryConfig.DEFAULT_RETRY_BACKOFF_STRATEGY);
        config.getScheduler().shutdown();
    }

    @Test
    void build_withoutEventSerializer_fails() {
        // When / Then
        assertThatThrownBy(() -> complete().eventSerializer(null).build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void build_zeroPollingDelay_fails() {
        // When / Then
        assertThatThrownBy(() -> complete().pollingDelay(Duration.ZERO).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Durable delivery pollingDelay must be greater than zero; got PT0S");
    }

    @Test
    void build_negativeStallWarningAfter_fails() {
        // When / Then
        assertThatThrownBy(() -> complete().stallWarningAfter(Duration.ofSeconds(-1)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Durable delivery stallWarningAfter must be greater than zero; got PT-1S");
    }

    private static DurableDeliveryConfig.Builder complete() {
        return DurableDeliveryConfig.withDefaults()
            .connectionProvider(new UnusedConnectionProvider())
            .transactionProvider(new UnusedTransactionProvider())
            .eventSerializer(serializer());
    }

    private static EventSerializer serializer() {
        return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
    }

    private static class UnusedConnectionProvider implements ConnectionProvider {

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseConnection(Connection connection) {
            throw new UnsupportedOperationException();
        }
    }

    private static class UnusedTransactionProvider implements TransactionProvider {

        @Override
        public void defer(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T doInTransaction(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void doInTransaction(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T doInNewTransaction(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void doInNewTransaction(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }
    }
}
