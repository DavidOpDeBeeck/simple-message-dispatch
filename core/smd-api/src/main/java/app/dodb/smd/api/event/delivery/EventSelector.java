package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

@FunctionalInterface
public interface EventSelector {

    @SafeVarargs
    static EventSelector eventType(Class<? extends Event> eventType, Class<? extends Event>... moreEventTypes) {
        var selectedEventTypes = new ArrayList<Class<? extends Event>>();
        selectedEventTypes.add(requireNonNull(eventType));
        selectedEventTypes.addAll(List.of(moreEventTypes));
        return eventMessage -> selectedEventTypes.stream().anyMatch(selected -> selected.isInstance(eventMessage.payload()));
    }

    static EventSelector eventPackage(Package eventPackage) {
        var packageName = eventPackage.getName();
        return eventMessage -> {
            var payloadPackage = eventMessage.payload().getClass().getPackageName();
            return payloadPackage.equals(packageName) || payloadPackage.startsWith(packageName + ".");
        };
    }

    static EventSelector metadataProperty(String propertyName) {
        var selectedPropertyName = requireNonNull(propertyName);
        return eventMessage -> eventMessage.metadata().properties().contains(selectedPropertyName);
    }

    static EventSelector metadataProperty(String propertyName, String propertyValue) {
        var selectedPropertyName = requireNonNull(propertyName);
        var selectedPropertyValue = requireNonNull(propertyValue);
        return eventMessage -> eventMessage.metadata().property(selectedPropertyName).filter(selectedPropertyValue::equals).isPresent();
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
