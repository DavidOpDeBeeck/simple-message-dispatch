package app.dodb.smd.durable.storage;

import app.dodb.smd.serialization.SerializedEvent;

import static java.util.Objects.requireNonNull;

public record PendingDelivery(long id, SerializedEvent event, int attempts) {

    public PendingDelivery {
        requireNonNull(event);
    }
}
