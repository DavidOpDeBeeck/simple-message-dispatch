package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.eventstore.serialization.JacksonDeadlineSerializer;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryDeadlineStorage;
import app.dodb.smd.test.NoOpTransactionProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineStoreConfigTest {

    @Test
    void build_withZeroPollingDelay_throws() {
        assertThatThrownBy(() -> completeConfig().pollingDelay(Duration.ZERO).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Deadline store pollingDelay must be greater than zero: PT0S");
    }

    @Test
    void build_withNegativeInitialDelay_throws() {
        assertThatThrownBy(() -> completeConfig().initialDelay(Duration.ofMillis(-1)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Deadline store initialDelay must not be negative: PT-0.001S");
    }

    @Test
    void build_withZeroBatchSize_throws() {
        assertThatThrownBy(() -> completeConfig().batchSize(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Deadline store batchSize must be greater than zero: 0");
    }

    @Test
    void build_withNegativeMaxRetries_throws() {
        assertThatThrownBy(() -> completeConfig().maxRetries(-1).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Deadline store maxRetries must not be negative: -1");
    }

    @Test
    void build_withoutDeadlineStorage_throws() {
        assertThatThrownBy(() -> completeConfig().deadlineStorage(null).build())
            .isInstanceOf(NullPointerException.class);
    }

    private static DeadlineStoreConfig.Builder completeConfig() {
        return DeadlineStoreConfig.withDefaults()
            .transactionProvider(new NoOpTransactionProvider())
            .principalProvider(new SimplePrincipalProvider())
            .timeProvider(new SystemTimeProvider())
            .deadlineStorage(new InMemoryDeadlineStorage())
            .deadlineSerializer(new JacksonDeadlineSerializer(JsonMapper.builder().build()));
    }
}
