package app.dodb.smd.api.event;

import app.dodb.smd.api.utils.CollectionUtils;
import app.dodb.smd.api.utils.LoggingUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public record EventHandlerRegistry(Map<String, List<AnnotatedEventHandler<?>>> eventHandlersByProcessingGroup) {

    public static EventHandlerRegistry empty() {
        return new EventHandlerRegistry(new HashMap<>());
    }

    public static EventHandlerRegistry from(Set<AnnotatedEventHandler<?>> eventHandlers) {
        var eventHandlersByProcessingGroup = eventHandlers.stream()
            .collect(groupingBy(EventHandlerBehaviour::processingGroup));
        return new EventHandlerRegistry(eventHandlersByProcessingGroup);
    }

    public EventHandlerRegistry {
        requireNonNull(eventHandlersByProcessingGroup);
        validateUniqueOrder(eventHandlersByProcessingGroup);
    }

    @SuppressWarnings("unchecked")
    public <E extends Event> Map<String, List<EventHandlerBehaviour<E>>> findBy(EventMessage<E> eventMessage) {
        Object payload = eventMessage.getPayload();

        return eventHandlersByProcessingGroup.entrySet().stream()
            .map(entry -> entry(
                entry.getKey(),
                entry.getValue().stream()
                    .filter(eventHandler -> eventHandler.eventType().isAssignableFrom(payload.getClass()))
                    .map(eventHandler -> (EventHandlerBehaviour<E>) eventHandler)
                    .sorted(comparing(EventHandlerBehaviour::order))
                    .collect(toList())
            ))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public EventHandlerRegistry and(EventHandlerRegistry other) {
        var combined = Stream.of(eventHandlersByProcessingGroup, other.eventHandlersByProcessingGroup)
            .flatMap(map -> map.entrySet().stream())
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, CollectionUtils::combine));

        return new EventHandlerRegistry(combined);
    }

    private void validateUniqueOrder(Map<String, List<AnnotatedEventHandler<?>>> eventHandlersByProcessingGroup) {
        eventHandlersByProcessingGroup.forEach((processingGroup, eventHandlers) -> {
            for (AnnotatedEventHandler<?> eventHandler : eventHandlers) {
                var overlappingHandlersWithSameOrder = eventHandlers.stream()
                    .filter(handler -> eventHandler.eventType().isAssignableFrom(handler.eventType()))
                    .filter(handler -> eventHandler.order() == handler.order())
                    .toList();

                if (overlappingHandlersWithSameOrder.size() > 1) {
                    throw new IllegalArgumentException("""
                        Event handlers with same order found:
                        
                        Processing group:
                        %s
                        
                        Event:
                        %s
                        
                        Methods:
                        %s
                        """.formatted(
                        processingGroup,
                        eventHandler.eventType().getName(),
                        overlappingHandlersWithSameOrder.stream()
                            .map(AnnotatedEventHandler::method)
                            .map(LoggingUtils::logMethod)
                            .collect(joining("\n"))));
                }
            }
        });
    }
}
