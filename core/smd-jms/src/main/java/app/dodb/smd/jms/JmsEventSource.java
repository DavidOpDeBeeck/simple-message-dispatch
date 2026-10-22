package app.dodb.smd.jms;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;

public class JmsEventSource implements EventSource, AutoCloseable {

    private final JmsConfig config;
    private final JmsDestination destination;
    private final List<JmsSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private String queueProcessingGroup;

    public JmsEventSource(JmsConfig config, JmsDestination destination) {
        this.config = requireNonNull(config);
        this.destination = requireNonNull(destination);
    }

    @Override
    public EventSubscription subscribe(EventSubscriber subscriber) {
        var subscription = new JmsSubscription(config, destination, subscriptionNameFor(subscriber.processingGroup()), subscriber);
        subscriptions.add(subscription);
        subscription.start();
        return () -> {
            subscription.close();
            subscriptions.remove(subscription);
        };
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

    private synchronized Optional<String> subscriptionNameFor(String processingGroup) {
        if (destination.kind() == JmsDestination.Kind.QUEUE) {
            if (queueProcessingGroup != null && !queueProcessingGroup.equals(processingGroup)) {
                throw new IllegalStateException(destination + " already feeds processing group '" + queueProcessingGroup
                    + "'; a queue has no copy per group, so it cannot also feed processing group '" + processingGroup + "'");
            }
            queueProcessingGroup = processingGroup;
            return Optional.empty();
        }

        var subscriberName = config.getSubscriberName().orElseThrow(() -> new IllegalStateException(
            destination + " needs JmsConfig.subscriberName to name the durable subscription for processing group '" + processingGroup + "'"));
        return Optional.of(subscriberName + "." + processingGroup);
    }
}
