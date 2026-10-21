package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.TokenStore;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class EventStore implements EventMedium, AutoCloseable {

    public static final EventDelivery DELIVERY = new EventDelivery("event-store");

    private final EventStoreSink sink;
    private final EventStoreSource source;
    private final ProcessingGroupReset processingGroupReset;
    private final TokenStore tokenStore;
    private final EventStorage eventStorage;

    public EventStore(EventStoreConfig config) {
        requireNonNull(config);
        this.sink = new EventStoreSink(config);
        this.source = new EventStoreSource(config);
        this.processingGroupReset = new ProcessingGroupReset(config, source::subscribes);
        this.tokenStore = config.getTokenStore();
        this.eventStorage = config.getEventStorage();
    }

    @Override
    public EventSink inlet() {
        return sink;
    }

    @Override
    public EventSource outlet() {
        return source;
    }

    public ResetResult reset(String processingGroup) {
        return reset(processingGroup, () -> {
        });
    }

    public ResetResult reset(String processingGroup, Runnable resetAction) {
        return processingGroupReset.reset(processingGroup, resetAction);
    }

    public Optional<ProcessingProgress> progress(String processingGroup) {
        return tokenStore.tokenState(requireNonNull(processingGroup)).map(state -> new ProcessingProgress(
            processingGroup,
            state.lastProcessedSequenceNumber().orElse(0L),
            eventStorage.headSequenceNumber(),
            state.replayUntilSequenceNumber()
        ));
    }

    @Override
    public void close() {
        source.close();
    }
}
