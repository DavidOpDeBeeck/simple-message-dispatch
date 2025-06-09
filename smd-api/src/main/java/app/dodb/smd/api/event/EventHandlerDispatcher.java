package app.dodb.smd.api.event;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class EventHandlerDispatcher {

    private final EventHandlerRegistry eventHandlerRegistry;

    public EventHandlerDispatcher(EventHandlerLocator eventHandlerLocator) {
        this.eventHandlerRegistry = requireNonNull(eventHandlerLocator).locate();
    }

    public <E extends Event> void dispatch(EventMessage<E> eventMessage) {
        try (var executor = newVirtualThreadPerTaskExecutor()) {
            var eventHandlersByProcessingGroup = eventHandlerRegistry.findBy(eventMessage);
            var futures = new ArrayList<Future<?>>();

            for (var eventHandlers : eventHandlersByProcessingGroup.values()) {
                futures.add(executor.submit(() -> eventHandlers.forEach(eventHandler -> eventHandler.handle(eventMessage))));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw rethrow(e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (Exception e) {
            throw rethrow(e);
        }
    }
}
