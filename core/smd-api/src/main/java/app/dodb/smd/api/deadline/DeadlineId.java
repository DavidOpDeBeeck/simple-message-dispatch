package app.dodb.smd.api.deadline;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record DeadlineId(UUID value) {

    public static DeadlineId generate() {
        return new DeadlineId(UUID.randomUUID());
    }

    public DeadlineId {
        requireNonNull(value);
    }
}
