package app.dodb.smd.eventstore.channel;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static app.dodb.smd.eventstore.channel.EventStoreChannelConfig.ProcessingConfig;
import static app.dodb.smd.eventstore.channel.EventStoreChannelConfig.SchedulingConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStoreChannelConfigTest {

    @Test
    void schedulingConfig_rejectsNegativeInitialDelay() {
        assertThatThrownBy(() -> SchedulingConfig.withDefaults()
            .initialDelay(Duration.ofMillis(-1))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedulingConfig_rejectsZeroPollingDelay() {
        assertThatThrownBy(() -> SchedulingConfig.withDefaults()
            .pollingDelay(Duration.ZERO)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedulingConfig_rejectsNegativePollingDelay() {
        assertThatThrownBy(() -> SchedulingConfig.withDefaults()
            .pollingDelay(Duration.ofMillis(-1))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processingConfig_rejectsNegativeMaxRetries() {
        assertThatThrownBy(() -> ProcessingConfig.withDefaults()
            .maxRetries(-1)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processingConfig_rejectsZeroBatchSize() {
        assertThatThrownBy(() -> ProcessingConfig.withDefaults()
            .batchSize(0)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processingConfig_rejectsNegativeBatchSize() {
        assertThatThrownBy(() -> ProcessingConfig.withDefaults()
            .batchSize(-1)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processingConfig_rejectsNegativeGapTimeout() {
        assertThatThrownBy(() -> ProcessingConfig.withDefaults()
            .gapTimeout(Duration.ofMillis(-1))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }
}
