package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.event.Event;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticEventTypeResolverTest {

    @Test
    void whenMappingIsPresent() throws EventTypeResolutionException {
        var resolver = new StaticEventTypeResolver(Map.of("TestEvent", TestEvent.class));

        assertThat(resolver.eventTypeFor(new TestEvent())).isEqualTo("TestEvent");
        assertThat(resolver.eventClassFor("TestEvent")).isEqualTo(TestEvent.class);
    }

    @Test
    void whenMappingIsMissing_throwsException() {
        var resolver = new StaticEventTypeResolver(emptyMap());

        assertThatThrownBy(() -> resolver.eventTypeFor(new AnotherTestEvent()))
            .isInstanceOf(EventTypeResolutionException.class);
        assertThatThrownBy(() -> resolver.eventClassFor("AnotherTestEvent"))
            .isInstanceOf(EventTypeResolutionException.class);
    }

    record TestEvent() implements Event {
    }

    record AnotherTestEvent() implements Event {
    }
}
