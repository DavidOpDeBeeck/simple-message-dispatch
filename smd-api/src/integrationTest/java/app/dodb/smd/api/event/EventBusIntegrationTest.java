package app.dodb.smd.api.event;

import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class EventBusIntegrationTest {

    @Test
    void publish() {
        EventBus eventBus = new EventBus(
            new MetadataFactory(new SimplePrincipalProvider(), new LocalDatetimeProvider()),
            new EventHandlerDispatcher(new PackageBasedEventHandlerLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator())),
            emptyList()
        );

        var event = new TestEvent();
        eventBus.publish(event);

        assertThat(TestEventHandler.handledEvents).containsExactly(event);
    }

    @Test
    void publish_withInterceptor() {
        var interceptor = new EventBusInterceptorForTest();
        EventBus eventBus = new EventBus(
            new MetadataFactory(new SimplePrincipalProvider(), new LocalDatetimeProvider()),
            new EventHandlerDispatcher(new PackageBasedEventHandlerLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator())),
            List.of(interceptor)
        );

        var event = new TestEvent();
        eventBus.publish(event);

        assertThat(interceptor.getInterceptedEvents()).containsExactly(event);
    }
}