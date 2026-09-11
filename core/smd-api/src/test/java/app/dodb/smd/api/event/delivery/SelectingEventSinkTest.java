package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectingEventSinkTest {

    @Test
    void send_whenEventIsSelected_thenSendsSameMessageToDelegate() {
        var eventMessages = new ArrayList<EventMessage<?>>();
        var sink = new SelectingEventSink(recordingSink(eventMessages), message -> message.payload() instanceof SelectedEvent);
        var eventMessage = EventMessage.from(new SelectedEvent(), METADATA);

        sink.send(eventMessage);

        assertThat(eventMessages).hasSize(1);
        assertThat(eventMessages.getFirst()).isSameAs(eventMessage);
    }

    @Test
    void send_whenEventIsNotSelected_thenDoesNotSendMessageToDelegate() {
        var eventMessages = new ArrayList<EventMessage<?>>();
        var sink = new SelectingEventSink(recordingSink(eventMessages), message -> message.payload() instanceof SelectedEvent);

        sink.send(EventMessage.from(new OtherEvent(), METADATA));

        assertThat(eventMessages).isEmpty();
    }

    @Test
    void send_whenSelectorThrows_thenPropagatesFailureWithoutSendingMessage() {
        var failure = new IllegalStateException("Selection failed");
        var eventMessages = new ArrayList<EventMessage<?>>();
        var sink = new SelectingEventSink(recordingSink(eventMessages), message -> {
            throw failure;
        });

        assertThatThrownBy(() -> sink.send(EventMessage.from(new SelectedEvent(), METADATA)))
            .isSameAs(failure);
        assertThat(eventMessages).isEmpty();
    }

    @Test
    void construct_withNullDelegate_thenRejectsDelegate() {
        assertThatNullPointerException()
            .isThrownBy(() -> new SelectingEventSink(null, message -> true));
    }

    @Test
    void construct_withNullSelector_thenRejectsSelector() {
        assertThatNullPointerException()
            .isThrownBy(() -> new SelectingEventSink(recordingSink(new ArrayList<>()), null));
    }

    private static EventSink recordingSink(List<EventMessage<?>> eventMessages) {
        return eventMessages::add;
    }

    private record SelectedEvent() implements Event {
    }

    private record OtherEvent() implements Event {
    }
}
