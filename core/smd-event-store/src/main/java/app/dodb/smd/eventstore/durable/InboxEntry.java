package app.dodb.smd.eventstore.durable;

import static java.util.Objects.requireNonNull;

record InboxEntry(long id, TransportEvent event, int attempts) {

    InboxEntry {
        requireNonNull(event);
    }
}
