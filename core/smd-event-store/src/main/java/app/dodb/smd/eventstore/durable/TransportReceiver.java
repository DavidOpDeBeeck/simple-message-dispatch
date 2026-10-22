package app.dodb.smd.eventstore.durable;

@FunctionalInterface
public interface TransportReceiver {

    void receive(TransportEvent event);
}
