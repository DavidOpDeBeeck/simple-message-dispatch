package app.dodb.smd.serialization.jackson;

import tools.jackson.databind.node.ObjectNode;

import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

public final class EventUpcaster {

    private final String eventType;
    private final String upcastedEventType;
    private final UnaryOperator<ObjectNode> transformation;

    private EventUpcaster(String eventType, String upcastedEventType, UnaryOperator<ObjectNode> transformation) {
        this.eventType = requireNonNull(eventType);
        this.upcastedEventType = requireNonNull(upcastedEventType);
        this.transformation = requireNonNull(transformation);
    }

    public static EventUpcaster rename(String eventType, String upcastedEventType) {
        return new EventUpcaster(eventType, upcastedEventType, UnaryOperator.identity());
    }

    public static EventUpcaster transform(String eventType, String upcastedEventType, UnaryOperator<ObjectNode> transformation) {
        return new EventUpcaster(eventType, upcastedEventType, transformation);
    }

    String eventType() {
        return eventType;
    }

    String upcastedEventType() {
        return upcastedEventType;
    }

    ObjectNode upcast(ObjectNode payload) {
        return transformation.apply(payload);
    }

    @Override
    public String toString() {
        return "EventUpcaster['%s' -> '%s']".formatted(eventType, upcastedEventType);
    }
}
