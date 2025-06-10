package app.dodb.smd.api.event;

import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.PrincipalProviderImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusIntegrationTest {

    @Test
    void publish() {
        EventBus eventBus = new EventBus(
            new MetadataFactory(new PrincipalProviderImpl(), new LocalDatetimeProvider()),
            new EventHandlerDispatcher(new PackageBasedEventHandlerLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
        );

        var event = new TestEvent();
        eventBus.publish(event);

        assertThat(TestEventHandler.handledEvents).containsExactly(event);
    }
}