package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventRoute;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRouteTest {

    private final SynchronousEventDispatcher upstream = new SynchronousEventDispatcher();
    private final SynchronousEventDispatcher downstream = new SynchronousEventDispatcher();

    @Test
    void route_eventFromSource_isSentToSink() {
        // Given
        var received = record(downstream, "reader");
        EventBusSpec.withDefaults().route("forward", upstream.outlet(), downstream.inlet()).create();
        var message = EventMessage.from(new RoutedEvent("hello"), METADATA);

        // When
        upstream.send(message);

        // Then
        assertThat(received).containsExactly(message);
    }

    @Test
    void route_withSinkInterceptors_interceptsEachSendWithItsRoute() {
        // Given
        var interceptions = new CopyOnWriteArrayList<String>();
        var route = new EventRoute("forward", upstream.outlet(), downstream.inlet());
        EventBusSpec.withDefaults()
            .sinkInterceptors(new EventSinkInterceptor() {
                @Override
                public <E extends Event> void intercept(EventSinkContext<E> context, EventSinkInterceptorChain<E> chain) {
                    interceptions.add(context.route().map(EventRoute::name).orElse("publish") + ":" + (context.sink() == downstream.inlet()));
                    chain.proceed(context);
                }
            })
            .routes(route)
            .create();

        // When
        upstream.send(EventMessage.from(new RoutedEvent("hello"), METADATA));

        // Then
        assertThat(interceptions).containsExactly("forward:true");
    }

    @Test
    void route_sinkFails_failsTheSourceDelivery() {
        // Given
        var failure = new IllegalStateException("Broker unavailable");
        downstream.subscribe(new RecordingSubscriber("reader", _ -> {
            throw failure;
        }));
        EventBusSpec.withDefaults().route("forward", upstream.outlet(), downstream.inlet()).create();

        // When / Then
        assertThatThrownBy(() -> upstream.send(EventMessage.from(new RoutedEvent("hello"), METADATA))).isSameAs(failure);
    }

    @Test
    void create_twoRoutesWithTheSameName_throwsException() {
        // Given
        var spec = EventBusSpec.withDefaults()
            .route("forward", upstream.outlet(), downstream.inlet())
            .route("forward", downstream.outlet(), upstream.inlet());

        // When / Then
        assertThatThrownBy(spec::create)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Event route 'forward' has the name of another route or processing group.");
    }

    @Test
    void create_twoRoutesWithTheSameName_subscribesNothing() {
        // Given
        var received = record(downstream, "reader");
        var spec = EventBusSpec.withDefaults()
            .route("forward", upstream.outlet(), downstream.inlet())
            .route("forward", downstream.outlet(), upstream.inlet());

        // When
        assertThatThrownBy(spec::create).isInstanceOf(IllegalArgumentException.class);
        upstream.send(EventMessage.from(new RoutedEvent("hello"), METADATA));

        // Then
        assertThat(received).isEmpty();
    }

    @Test
    void newEventRoute_blankName_throwsException() {
        // When / Then
        assertThatThrownBy(() -> new EventRoute(" ", upstream.outlet(), downstream.inlet()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Event route requires a non-blank name; got ' '");
    }

    private static List<EventMessage<?>> record(SynchronousEventDispatcher medium, String name) {
        var received = new CopyOnWriteArrayList<EventMessage<?>>();
        medium.subscribe(new RecordingSubscriber(name, received::add));
        return received;
    }

    private record RoutedEvent(String value) implements Event {
    }

    private record RecordingSubscriber(String name, Consumer<EventMessage<?>> onEvent) implements EventSubscriber {

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            onEvent.accept(eventMessage);
        }
    }
}
