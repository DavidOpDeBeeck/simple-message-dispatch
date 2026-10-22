package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.eventstore.durable.TransportEvent;
import app.dodb.smd.eventstore.durable.TransportReceiver;
import app.dodb.smd.eventstore.durable.TransportSink;
import app.dodb.smd.eventstore.durable.TransportSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class LoopbackTransport implements TransportSink, TransportSource {

    private final List<TransportEvent> sent = new CopyOnWriteArrayList<>();
    private final List<TransportReceiver> receivers = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();

    void failNextSends(int count) {
        failuresLeft.set(count);
    }

    List<TransportEvent> sent() {
        return sent;
    }

    void deliver(TransportEvent event) {
        receivers.forEach(receiver -> receiver.receive(event));
    }

    @Override
    public void send(TransportEvent event) {
        if (failuresLeft.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
            throw new IllegalStateException("Broker unavailable");
        }
        sent.add(event);
        deliver(event);
    }

    @Override
    public EventSubscription subscribe(String subscriberName, TransportReceiver receiver) {
        receivers.add(receiver);
        return () -> receivers.remove(receiver);
    }
}
