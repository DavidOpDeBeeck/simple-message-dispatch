package app.dodb.smd.eventstore.storage;

import static java.util.Objects.requireNonNull;

public record EventLog(String name) {

    public static final EventLog EVENTS = new EventLog("events");

    private static final int MAX_NAME_LENGTH = 255;

    public static EventLog outbox(String outboxName) {
        return new EventLog("outbox:" + requireNonBlank(outboxName, "outbox name"));
    }

    public static EventLog inbox(String processingGroup) {
        return new EventLog("inbox:" + requireNonBlank(processingGroup, "processing group"));
    }

    public EventLog {
        requireNonBlank(name, "event log name");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Event log name must be at most " + MAX_NAME_LENGTH + " characters; got '" + name + "'");
        }
    }

    private static String requireNonBlank(String value, String description) {
        requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Event log requires a non-blank " + description + "; got '" + value + "'");
        }
        return value;
    }
}
