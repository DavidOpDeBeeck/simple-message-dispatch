package app.dodb.smd.serialization;

import app.dodb.smd.api.event.Event;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticEventTypeResolverTest {

    @Test
    void resolve_mappedEvent_returnsEventTypeAndEventClass() {
        var resolver = new StaticEventTypeResolver(Map.of("TestEvent", TestEvent.class));

        assertThat(resolver.eventTypeFor(new TestEvent())).contains("TestEvent");
        assertThat(resolver.eventClassFor("TestEvent")).contains(TestEvent.class);
    }

    @Test
    void resolve_unmappedEvent_returnsEmpty() {
        var resolver = new StaticEventTypeResolver(emptyMap());

        assertThat(resolver.eventTypeFor(new AnotherTestEvent())).isEmpty();
        assertThat(resolver.eventClassFor("AnotherTestEvent")).isEmpty();
    }

    @Test
    void constructor_eventClassMappedToTwoEventTypes_throwsExceptionNamingBoth() {
        var eventClassByEventType = Map.<String, Class<? extends Event>>of(
            "test.v1", TestEvent.class,
            "test.v2", TestEvent.class
        );

        assertThatThrownBy(() -> new StaticEventTypeResolver(eventClassByEventType))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Event class " + TestEvent.class.getName() + " is mapped to event types ")
            .hasMessageContaining("'test.v1'")
            .hasMessageContaining("'test.v2'");
    }

    record TestEvent() implements Event {
    }

    record AnotherTestEvent() implements Event {
    }
}
