package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import app.dodb.smd.api.metadata.MetadataFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static app.dodb.smd.api.metadata.MetadataTestConstants.PRINCIPAL;
import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

    private static final Instant BUS_TIME = Instant.parse("2026-10-30T12:00:00Z");

    private final SynchronousEventDispatcher dispatcher = new SynchronousEventDispatcher();

    @Test
    void publish_metadataUpdateWhileHandlingAMessage_keepsLineageAndAppliesTheUpdate() {
        var received = record(dispatcher);
        var eventBus = EventBusSpec.withDefaults().time(() -> BUS_TIME).sinks(dispatcher.inlet()).create();
        var handled = EventMessage.from(new HandledEvent(), METADATA.withProperty("tenant", "acme"));

        MetadataFactory.runInScope(handled, () -> eventBus.publish(new PublishedEvent(), metadata -> metadata.withProperty("region", "eu")));

        assertThat(received).singleElement().satisfies(message -> {
            assertThat(message.metadata().parentMessageId()).isEqualTo(handled.messageId());
            assertThat(message.metadata().principal()).isEqualTo(PRINCIPAL);
            assertThat(message.metadata().timestamp()).isEqualTo(BUS_TIME);
            assertThat(message.metadata().properties().asMap()).containsOnly(Map.entry("tenant", "acme"), Map.entry("region", "eu"));
        });
    }

    private static List<EventMessage<?>> record(SynchronousEventDispatcher medium) {
        var received = new CopyOnWriteArrayList<EventMessage<?>>();
        medium.subscribe(new RecordingSubscriber(received));
        return received;
    }

    private record HandledEvent() implements Event {
    }

    private record PublishedEvent() implements Event {
    }

    private record RecordingSubscriber(List<EventMessage<?>> received) implements EventSubscriber {

        @Override
        public String name() {
            return "recorder";
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            received.add(eventMessage);
        }
    }
}
