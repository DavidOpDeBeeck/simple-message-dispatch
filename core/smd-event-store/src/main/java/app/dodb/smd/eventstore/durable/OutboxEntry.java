package app.dodb.smd.eventstore.durable;

import static java.util.Objects.requireNonNull;

record OutboxEntry(long id, TransportEvent event, int attempts) {

    OutboxEntry {
        requireNonNull(event);
    }
}
