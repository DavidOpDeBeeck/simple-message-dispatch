package app.dodb.smd.eventstore.durable;

public interface TransportSink {

    // Return only once the transport has stored the event; a failure is retried by the outbox.
    void send(TransportEvent event);
}
