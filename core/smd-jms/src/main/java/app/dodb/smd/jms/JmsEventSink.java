package app.dodb.smd.jms;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;

import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

public class JmsEventSink implements EventSink, AutoCloseable {

    private final JmsConfig config;
    private final JmsDestination destination;
    private final ReentrantLock lock = new ReentrantLock();
    private JMSContext context;

    public JmsEventSink(JmsConfig config, JmsDestination destination) {
        this.config = requireNonNull(config);
        this.destination = requireNonNull(destination);
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        var serializedEvent = config.getEventSerializer().serialize(eventMessage);

        // A JMSContext is single-threaded; sends share one connection and take turns.
        lock.lock();
        try {
            var activeContext = context();
            var message = JmsWireFormat.toMessage(activeContext, serializedEvent, eventMessage.metadata().properties());
            activeContext.createProducer()
                .setDeliveryMode(DeliveryMode.PERSISTENT)
                .send(destination.createIn(activeContext), message);
        } catch (JMSException | JMSRuntimeException e) {
            closeContext();
            throw new JmsDeliveryException("Failed to send event to " + destination + ": messageId=" + eventMessage.messageId().value()
                + ", eventType=" + serializedEvent.eventType(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public EventDelivery delivery() {
        return JmsEventMedium.DELIVERY;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeContext();
        } finally {
            lock.unlock();
        }
    }

    private JMSContext context() {
        if (context == null) {
            context = config.getConnectionFactory().createContext(JMSContext.AUTO_ACKNOWLEDGE);
        }
        return context;
    }

    private void closeContext() {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (JMSRuntimeException ignored) {
            // The connection is already broken; the next send opens a new one.
        } finally {
            context = null;
        }
    }
}
