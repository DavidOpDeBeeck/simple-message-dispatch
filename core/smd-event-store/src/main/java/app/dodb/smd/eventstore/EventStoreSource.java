package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.eventstore.storage.EventLog;

import static app.dodb.smd.eventstore.EventLogConsumption.HANDLING;
import static java.util.Objects.requireNonNull;

class EventStoreSource implements EventSource {

    private final EventLogPolling polling;
    private final EventStoreTokenProcessor tokenProcessor;

    EventStoreSource(EventStoreConfig config, EventLogPolling polling) {
        requireNonNull(config);
        this.polling = requireNonNull(polling);
        this.tokenProcessor = new EventStoreTokenProcessor(config, EventLog.EVENTS, HANDLING);
    }

    @Override
    public EventSubscription subscribe(EventSubscriber listener) {
        return polling.schedule(tokenProcessor, listener);
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }
}
