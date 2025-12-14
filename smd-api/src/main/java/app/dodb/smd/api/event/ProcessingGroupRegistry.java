package app.dodb.smd.api.event;

import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.utils.CollectionUtils;
import app.dodb.smd.api.utils.LoggingUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public record ProcessingGroupRegistry(Map<String, EventHandlerRegistry> eventHandlerRegistryByProcessingGroup) {

    public static ProcessingGroupRegistry empty() {
        return new ProcessingGroupRegistry(new HashMap<>());
    }

    public static ProcessingGroupRegistry from(Set<AnnotatedEventHandler<?>> eventHandlers) {
        var eventHandlerRegistryByProcessingGroup = eventHandlers.stream()
            .collect(groupingBy(EventHandlerBehaviour::processingGroup, collectingAndThen(toList(), EventHandlerRegistry::new)));
        return new ProcessingGroupRegistry(eventHandlerRegistryByProcessingGroup);
    }

    public ProcessingGroupRegistry {
        requireNonNull(eventHandlerRegistryByProcessingGroup);
        validateUniqueOrder(eventHandlerRegistryByProcessingGroup);
    }

    public EventHandlerRegistry findBy(String processingGroup) {
        return eventHandlerRegistryByProcessingGroup.getOrDefault(processingGroup, EventHandlerRegistry.empty());
    }

    public ProcessingGroupRegistry combine(ProcessingGroupRegistry other) {
        var combined = Stream.of(eventHandlerRegistryByProcessingGroup, other.eventHandlerRegistryByProcessingGroup)
            .flatMap(map -> map.entrySet().stream())
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, EventHandlerRegistry::combine));

        return new ProcessingGroupRegistry(combined);
    }

    private void validateUniqueOrder(Map<String, EventHandlerRegistry> eventHandlerRegistryByProcessingGroup) {
        eventHandlerRegistryByProcessingGroup.forEach((processingGroup, registry) -> {
            for (AnnotatedEventHandler<?> eventHandler : registry.eventHandlers()) {
                var overlappingHandlersWithSameOrder = registry.eventHandlers().stream()
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

    public record EventHandlerRegistry(List<AnnotatedEventHandler<?>> eventHandlers) implements EventChannelListener {

        public static EventHandlerRegistry empty() {
            return new EventHandlerRegistry(emptyList());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            var payload = eventMessage.getPayload();

            eventHandlers.stream()
                .filter(eventHandler -> eventHandler.eventType().isAssignableFrom(payload.getClass()))
                .map(eventHandler -> (EventHandlerBehaviour<E>) eventHandler)
                .sorted(Comparator.comparing(EventHandlerBehaviour::order))
                .forEach(eventHandler -> eventHandler.handle(eventMessage));
        }

        public EventHandlerRegistry combine(EventHandlerRegistry registry) {
            var combined = CollectionUtils.combine(eventHandlers, registry.eventHandlers);
            return new EventHandlerRegistry(combined);
        }
    }
}
