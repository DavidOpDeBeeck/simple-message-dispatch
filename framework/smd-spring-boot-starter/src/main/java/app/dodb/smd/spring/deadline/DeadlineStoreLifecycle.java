package app.dodb.smd.spring.deadline;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.eventstore.deadline.DeadlineStore;
import org.springframework.context.SmartLifecycle;

import static java.util.Objects.requireNonNull;

// Starts firing once the context is refreshed, so handlers that schedule deadlines and the gateway that fires them exist first.
public class DeadlineStoreLifecycle implements SmartLifecycle {

    private final DeadlineStore deadlineStore;
    private final CommandGateway commandGateway;
    private volatile boolean running;

    public DeadlineStoreLifecycle(DeadlineStore deadlineStore, CommandGateway commandGateway) {
        this.deadlineStore = requireNonNull(deadlineStore);
        this.commandGateway = requireNonNull(commandGateway);
    }

    @Override
    public void start() {
        deadlineStore.start(commandGateway);
        running = true;
    }

    @Override
    public void stop() {
        deadlineStore.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
