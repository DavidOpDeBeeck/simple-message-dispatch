package app.dodb.smd.eventstore.deadline;

@FunctionalInterface
public interface DeadlineSubscription extends AutoCloseable {

    @Override
    void close();
}
