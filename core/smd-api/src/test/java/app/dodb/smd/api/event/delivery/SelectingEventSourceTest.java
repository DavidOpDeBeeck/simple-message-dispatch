package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static app.dodb.smd.api.event.delivery.EventSelector.eventType;
import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectingEventSourceTest {

    @Test
    void subscribe_thenPreservesListenerProcessingGroup() {
        var subscribedSubscriber = new AtomicReference<EventSubscriber>();
        EventSource delegate = subscriber -> {
            subscribedSubscriber.set(subscriber);
            return EventSubscription.EMPTY;
        };
        var source = new SelectingEventSource(delegate, message -> true);

        try (var _ = source.subscribe(new RecordingEventSubscriber("ticket-view"))) {
            assertThat(subscribedSubscriber.get().processingGroup()).isEqualTo("ticket-view");
        }
    }

    @Test
    void receive_whenEventIsSelected_thenDeliversSameMessageToListener() {
        var delegate = new SynchronousEventDispatcher();
        var source = new SelectingEventSource(delegate.outlet(), eventType(SelectedEvent.class));
        var listener = new RecordingEventSubscriber("ticket-view");
        var eventMessage = EventMessage.from(new SelectedEvent(), METADATA);

        try (var _ = source.subscribe(listener)) {
            delegate.send(eventMessage);

            assertThat(listener.eventMessages).hasSize(1);
            assertThat(listener.eventMessages.getFirst()).isSameAs(eventMessage);
        }
    }

    @Test
    void receive_whenEventIsNotSelected_thenDoesNotDeliverMessageToListener() {
        var delegate = new SynchronousEventDispatcher();
        var source = new SelectingEventSource(delegate.outlet(), eventType(SelectedEvent.class));
        var listener = new RecordingEventSubscriber("ticket-view");

        try (var _ = source.subscribe(listener)) {
            delegate.send(EventMessage.from(new OtherEvent(), METADATA));

            assertThat(listener.eventMessages).isEmpty();
        }
    }

    @Test
    void receive_whenSelectorThrows_thenPropagatesFailureWithoutDeliveringMessage() {
        var failure = new IllegalStateException("Selection failed");
        var delegate = new SynchronousEventDispatcher();
        var source = new SelectingEventSource(delegate.outlet(), message -> {
            throw failure;
        });
        var listener = new RecordingEventSubscriber("ticket-view");

        try (var _ = source.subscribe(listener)) {
            assertThatThrownBy(() -> delegate.send(EventMessage.from(new SelectedEvent(), METADATA)))
                .isSameAs(failure);
            assertThat(listener.eventMessages).isEmpty();
        }
    }

    @Test
    void construct_withNullDelegate_thenRejectsDelegate() {
        assertThatNullPointerException()
            .isThrownBy(() -> new SelectingEventSource(null, message -> true));
    }

    @Test
    void construct_withNullSelector_thenRejectsSelector() {
        assertThatNullPointerException()
            .isThrownBy(() -> new SelectingEventSource(new SynchronousEventDispatcher().outlet(), null));
    }

    private static class RecordingEventSubscriber implements EventSubscriber {

        private final String processingGroup;
        private final List<EventMessage<?>> eventMessages = new ArrayList<>();

        private RecordingEventSubscriber(String processingGroup) {
            this.processingGroup = processingGroup;
        }

        @Override
        public String processingGroup() {
            return processingGroup;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            eventMessages.add(eventMessage);
        }
    }

    private record SelectedEvent() implements Event {
    }

    private record OtherEvent() implements Event {
    }
}
