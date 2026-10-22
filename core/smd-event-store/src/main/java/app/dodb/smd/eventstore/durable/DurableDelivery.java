package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class DurableDelivery implements AutoCloseable {

    public static final EventDelivery OUTBOX = new EventDelivery("outbox");
    public static final EventDelivery INBOX = new EventDelivery("inbox");

    private final DurableDeliveryConfig config;
    private final OutboxStorage outboxStorage;
    private final InboxStorage inboxStorage;
    private final Set<String> outboxNames = ConcurrentHashMap.newKeySet();
    private final Set<String> inboxNames = ConcurrentHashMap.newKeySet();
    private final List<DurableInbox> inboxes = new ArrayList<>();

    public DurableDelivery(DurableDeliveryConfig config) {
        this.config = requireNonNull(config);
        this.outboxStorage = new OutboxStorage(config.getConnectionProvider());
        this.inboxStorage = new InboxStorage(config.getConnectionProvider());
    }

    // Outbox names must be unique across every application that feeds the same inbox: an inbox orders events per outbox name and subject.
    public EventSink outbox(String name, TransportSink sink) {
        requireName("Outbox", name);
        requireNonNull(sink);
        if (!outboxNames.add(name)) {
            throw new IllegalStateException("Outbox '" + name + "' is already declared");
        }
        var pollingDelay = config.getPollingDelay().toMillis();
        config.getScheduler().scheduleWithFixedDelay(new OutboxRelay(name, sink, outboxStorage, config), 0, pollingDelay, MILLISECONDS);
        return new DurableOutbox(name, outboxStorage, config);
    }

    // Starts receiving from every source immediately; the returned source delivers to each processing group that subscribes to it.
    public EventSource inbox(String name, TransportSource... sources) {
        requireName("Inbox", name);
        if (sources.length == 0) {
            throw new IllegalArgumentException("Inbox '" + name + "' requires at least one transport source");
        }
        if (!inboxNames.add(name)) {
            throw new IllegalStateException("Inbox '" + name + "' is already declared");
        }
        var inbox = new DurableInbox(name, List.of(sources), inboxStorage, config);
        synchronized (inboxes) {
            inboxes.add(inbox);
        }
        return inbox;
    }

    @Override
    public void close() {
        synchronized (inboxes) {
            inboxes.forEach(DurableInbox::close);
            inboxes.clear();
        }
        var scheduler = config.getScheduler();
        scheduler.shutdown();
        try {
            if (scheduler.awaitTermination(30, SECONDS)) {
                return;
            }
            scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void requireName(String kind, String name) {
        requireNonNull(name);
        if (name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException(kind + " name must contain 1 to 255 characters; got '" + name + "'");
        }
    }
}
