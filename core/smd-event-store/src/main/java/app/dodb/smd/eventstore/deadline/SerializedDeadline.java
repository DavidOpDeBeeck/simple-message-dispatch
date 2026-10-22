package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.deadline.DeadlineId;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public record SerializedDeadline(DeadlineId deadlineId,
                                 String commandType,
                                 byte[] serializedPayload,
                                 byte[] serializedMetadata,
                                 Instant dueAt) {

    public SerializedDeadline {
        requireNonNull(deadlineId);
        requireNonNull(commandType);
        requireNonNull(serializedPayload);
        requireNonNull(serializedMetadata);
        requireNonNull(dueAt);
    }
}
