package app.dodb.smd.api.event;

import static java.util.Objects.requireNonNull;

public record Subject(String type, String id) {

    public Subject {
        requireNonNull(type);
        requireNonNull(id);
    }
}
