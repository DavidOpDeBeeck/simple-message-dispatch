package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventSourceStubTest {

    private static final Metadata METADATA = Metadata.withProperties(Map.of());

    @Test
    void send_withSubscribers_thenCapturesAndDeliversMessageInSubscriptionOrder() {
        var deliveries = new ArrayList<String>();
        var source = new EventSourceStub();
        source.subscribe(new EventSubscriberStub("first", eventMessage -> deliveries.add("first")));
        source.subscribe(new EventSubscriberStub("second", eventMessage -> deliveries.add("second")));
        var eventMessage = EventMessage.from(new EventForTest(), METADATA);

        source.send(eventMessage);

        assertThat(source.getEventMessages()).containsExactly(eventMessage);
        assertThat(deliveries).containsExactly("first", "second");
    }

    @Test
    void reset_withSubscription_thenClearsMessagesAndPreservesSubscription() {
        var deliveries = new ArrayList<EventMessage<?>>();
        var source = new EventSourceStub();
        source.subscribe(new EventSubscriberStub("test", deliveries::add));
        source.send(EventMessage.from(new EventForTest(), METADATA));

        source.reset();
        var eventMessageAfterReset = EventMessage.from(new EventForTest(), METADATA);
        source.send(eventMessageAfterReset);

        assertThat(source.getEventMessages()).containsExactly(eventMessageAfterReset);
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.getLast()).isSameAs(eventMessageAfterReset);
    }

    private record EventForTest() implements Event {
    }
}
