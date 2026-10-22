package app.dodb.smd.spring.deadline;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.eventstore.deadline.DeadlineStore;
import app.dodb.smd.eventstore.deadline.DeadlineSubscription;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

// Subscribes once the context is refreshed, so handlers that schedule deadlines and the gateway that fires them exist first.
public class DeadlineStoreLifecycle implements SmartLifecycle {

    private final DeadlineStore deadlineStore;
    private final CommandGateway commandGateway;
    private final AtomicReference<DeadlineSubscription> subscription = new AtomicReference<>();

    public DeadlineStoreLifecycle(DeadlineStore deadlineStore, CommandGateway commandGateway) {
        this.deadlineStore = requireNonNull(deadlineStore);
        this.commandGateway = requireNonNull(commandGateway);
    }

    @Override
    public void start() {
        subscription.set(deadlineStore.subscribe(commandGateway));
    }

    @Override
    public void stop() {
        var openSubscription = subscription.getAndSet(null);
        if (openSubscription != null) {
            openSubscription.close();
        }
    }

    @Override
    public boolean isRunning() {
        return subscription.get() != null;
    }
}
