package app.dodb.smd.eventstore.durable;

import static java.util.Objects.requireNonNull;

public record OutboxPosition(String outbox, long position) {

    public OutboxPosition {
        requireNonNull(outbox);
        if (outbox.isBlank() || outbox.length() > 255) {
            throw new IllegalArgumentException("Outbox position requires an outbox name of 1 to 255 characters; got '" + outbox + "'");
        }
        if (position < 1) {
            throw new IllegalArgumentException("Outbox position must be at least 1; got " + position + " for outbox '" + outbox + "'");
        }
    }
}
