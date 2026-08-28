package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.event.channel.SubscribableEventChannel;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.channel.processing.EventStoreTokenProcessor;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.channel.EventStoreChannelConfig.SchedulingConfig;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class EventStoreChannel implements SubscribableEventChannel, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreChannel.class);

    private final TransactionProvider transactionProvider;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;
    private final SchedulingConfig schedulingConfig;
    private final EventStoreTokenProcessor tokenProcessor;

    public EventStoreChannel(EventStoreChannelConfig config) {
        this.transactionProvider = config.getTransactionProvider();
        this.eventStorage = config.getEventStorage();
        this.eventSerializer = config.getEventSerializer();
        this.schedulingConfig = config.getSchedulingConfig();
        this.tokenProcessor = new EventStoreTokenProcessor(config);
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        transactionProvider.defer(() -> eventStorage.store(eventSerializer.serialize(eventMessage)));
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        if (!schedulingConfig.isEnabled()) {
            LOGGER.info("Event store polling disabled: processingGroup={}", listener.processingGroup());
            return;
        }

        var scheduler = schedulingConfig.getScheduler();
        scheduler.scheduleWithFixedDelay(
            () -> tokenProcessor.poll(listener),
            schedulingConfig.getInitialDelay().toMillis(),
            schedulingConfig.getPollingDelay().toMillis(),
            MILLISECONDS
        );
    }

    @Override
    public void close() {
        ScheduledExecutorService scheduler = schedulingConfig.getScheduler();
        scheduler.shutdown();
        try {
            if (scheduler.awaitTermination(30, SECONDS)) {
                return;
            }
            scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
