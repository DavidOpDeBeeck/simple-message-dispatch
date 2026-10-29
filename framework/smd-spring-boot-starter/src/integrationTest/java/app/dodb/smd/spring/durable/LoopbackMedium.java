package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class LoopbackMedium implements EventMedium {

    private static final EventDelivery DELIVERY = new EventDelivery("loopback");

    private final List<EventMessage<?>> sent = new CopyOnWriteArrayList<>();
    private final List<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    void failNextSends(int count) {
        failuresLeft.set(count);
    }

    List<EventMessage<?>> sent() {
        return sent;
    }

    void deliver(EventMessage<?> message) {
        subscribers.forEach(subscriber -> subscriber.on(message));
    }

    @Override
    public EventSink inlet() {
        return new EventSink() {
            @Override
            public <E extends Event> void send(EventMessage<E> message) {
                if (failuresLeft.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                    throw new IllegalStateException("Broker unavailable");
                }
                sent.add(message);
                deliver(message);
            }

            @Override
            public EventDelivery delivery() {
                return DELIVERY;
            }
        };
    }

    @Override
    public EventSource outlet() {
        return new EventSource() {
            @Override
            public EventSubscription subscribe(EventSubscriber subscriber) {
                subscribers.add(subscriber);
                return () -> subscribers.remove(subscriber);
            }

            @Override
            public EventDelivery delivery() {
                return DELIVERY;
            }
        };
    }
}
