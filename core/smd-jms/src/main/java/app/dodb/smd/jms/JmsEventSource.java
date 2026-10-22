package app.dodb.smd.jms;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.eventstore.durable.TransportReceiver;
import app.dodb.smd.eventstore.durable.TransportSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;

public class JmsEventSource implements EventSource, TransportSource, AutoCloseable {

    private final JmsConfig config;
    private final JmsDestination destination;
    private final List<JmsSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private String queueSubscriber;

    public JmsEventSource(JmsConfig config, JmsDestination destination) {
        this.config = requireNonNull(config);
        this.destination = requireNonNull(destination);
    }

    @Override
    public EventSubscription subscribe(EventSubscriber subscriber) {
        var processingGroup = subscriber.processingGroup();
        return start(processingGroup, message -> {
            EventMessage<Event> eventMessage = config.getEventSerializer().deserialize(JmsWireFormat.fromMessage(message));
            config.getTransactionProvider().doInNewTransaction(() ->
                MetadataFactory.runInScope(eventMessage, () -> subscriber.on(eventMessage)));
        });
    }

    // The receiver owns its transaction; the message is acknowledged once receive returns.
    @Override
    public EventSubscription subscribe(String subscriberName, TransportReceiver receiver) {
        requireNonNull(receiver);
        return start(subscriberName, message -> receiver.receive(JmsWireFormat.toTransportEvent(message)));
    }

    @Override
    public EventDelivery delivery() {
        return JmsEventMedium.DELIVERY;
    }

    @Override
    public void close() {
        subscriptions.forEach(JmsSubscription::close);
        subscriptions.clear();
    }

    private EventSubscription start(String subscriber, JmsSubscription.MessageHandler handler) {
        var subscription = new JmsSubscription(config, destination, subscriptionNameFor(subscriber), subscriber, handler);
        subscriptions.add(subscription);
        subscription.start();
        return () -> {
            subscription.close();
            subscriptions.remove(subscription);
        };
    }

    // Artemis names a shared subscription's queue after the subscription name alone, so the name includes the destination.
    private synchronized Optional<String> subscriptionNameFor(String subscriber) {
        if (destination.kind() == JmsDestination.Kind.QUEUE) {
            if (queueSubscriber != null && !queueSubscriber.equals(subscriber)) {
                throw new IllegalStateException(destination + " already feeds '" + queueSubscriber
                    + "'; a queue has no copy per subscriber, so it cannot also feed '" + subscriber + "'");
            }
            queueSubscriber = subscriber;
            return Optional.empty();
        }

        var subscriberName = config.getSubscriberName().orElseThrow(() -> new IllegalStateException(
            destination + " needs JmsConfig.subscriberName to name the durable subscription for '" + subscriber + "'"));
        return Optional.of(subscriberName + "." + subscriber + "." + destination.name());
    }
}
