package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import static java.util.Objects.requireNonNull;

@FunctionalInterface
public interface EventSelector {

    static EventSelector eventType(Class<? extends Event> eventType) {
        var selectedEventType = requireNonNull(eventType);
        return eventMessage -> selectedEventType.isInstance(eventMessage.payload());
    }

    static EventSelector metadataProperty(String propertyName) {
        var selectedPropertyName = requireNonNull(propertyName);
        return eventMessage -> eventMessage.metadata().properties().containsKey(selectedPropertyName);
    }

    static EventSelector metadataProperty(String propertyName, String propertyValue) {
        var selectedPropertyName = requireNonNull(propertyName);
        var selectedPropertyValue = requireNonNull(propertyValue);
        return eventMessage -> selectedPropertyValue.equals(eventMessage.metadata().properties().get(selectedPropertyName));
    }

    boolean selects(EventMessage<?> eventMessage);

    default EventSelector and(EventSelector selector) {
        var other = requireNonNull(selector);
        return eventMessage -> selects(eventMessage) && other.selects(eventMessage);
    }

    default EventSelector or(EventSelector selector) {
        var other = requireNonNull(selector);
        return eventMessage -> selects(eventMessage) || other.selects(eventMessage);
    }

    default EventSelector negate() {
        return eventMessage -> !selects(eventMessage);
    }
}
