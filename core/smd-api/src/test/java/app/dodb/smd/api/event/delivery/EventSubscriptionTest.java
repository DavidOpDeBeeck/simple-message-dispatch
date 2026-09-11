package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Stream;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class EventSubscriptionTest {

    @ParameterizedTest
    @MethodSource("dispatchers")
    void close_withDuplicateSubscriber_removesOnlyOwnRegistrationIdempotently(Function<ExecutorService, EventMedium> factory) throws Exception {
        try (var executor = newSingleThreadExecutor()) {
            var dispatcher = factory.apply(executor);
            var received = new CopyOnWriteArrayList<EventMessage<?>>();
            var subscriber = new RecordingSubscriber("test", received);
            var message = EventMessage.from(new TestEvent(), METADATA);

            try (var first = dispatcher.subscribe(subscriber);
                 var second = dispatcher.subscribe(subscriber)) {
                first.close();
                first.close();
                dispatcher.send(message);
                executor.submit(() -> {}).get(5, SECONDS);

                assertThat(received).containsExactly(message);

                second.close();
                dispatcher.send(message);
                executor.submit(() -> {}).get(5, SECONDS);

                assertThat(received).containsExactly(message);
            }
        }
    }

    private static Stream<Function<ExecutorService, EventMedium>> dispatchers() {
        List<Function<ExecutorService, EventMedium>> factories = List.of(
            _ -> new SynchronousEventDispatcher(),
            ConcurrentEventDispatcher::using,
            FireAndForgetEventDispatcher::using
        );
        return factories.stream().flatMap(factory -> Stream.of(
            factory,
            executor -> factory.apply(executor).selecting(EventSelector.eventType(TestEvent.class))
        ));
    }

    private record TestEvent() implements Event {
    }

    private record RecordingSubscriber(String processingGroup, List<EventMessage<?>> received) implements EventSubscriber {

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            received.add(eventMessage);
        }
    }
}
