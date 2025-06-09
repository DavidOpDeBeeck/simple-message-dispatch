package app.dodb.smd.api.message;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record MessageId(UUID value) {

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID());
    }

    public MessageId {
        requireNonNull(value);
    }
}
