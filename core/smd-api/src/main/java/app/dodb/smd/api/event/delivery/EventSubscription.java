package app.dodb.smd.api.event.delivery;

@FunctionalInterface
public interface EventSubscription extends AutoCloseable {

    EventSubscription EMPTY = () -> {
    };

    @Override
    void close();
}
