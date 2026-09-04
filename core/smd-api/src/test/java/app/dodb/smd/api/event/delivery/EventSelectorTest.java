package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static app.dodb.smd.api.event.delivery.EventSelector.eventType;
import static app.dodb.smd.api.event.delivery.EventSelector.metadataProperty;
import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EventSelectorTest {

    @Test
    void eventType_withMatchingImplementation_thenSelectsEvent() {
        var selector = eventType(AuditEvent.class);

        var selected = selector.selects(message(new AuditedEvent()));

        assertThat(selected).isTrue();
    }

    @Test
    void eventType_withDifferentEventType_thenDoesNotSelectEvent() {
        var selector = eventType(AuditEvent.class);

        var selected = selector.selects(message(new OtherEvent()));

        assertThat(selected).isFalse();
    }

    @Test
    void metadataProperty_withPresentProperty_thenSelectsEvent() {
        var selector = metadataProperty("audit");

        var selected = selector.selects(message(new AuditedEvent(), "audit", "true"));

        assertThat(selected).isTrue();
    }

    @Test
    void metadataProperty_withAbsentProperty_thenDoesNotSelectEvent() {
        var selector = metadataProperty("audit");

        var selected = selector.selects(message(new AuditedEvent()));

        assertThat(selected).isFalse();
    }

    @Test
    void metadataPropertyWithValue_withMatchingProperty_thenSelectsEvent() {
        var selector = metadataProperty("audit", "true");

        var selected = selector.selects(message(new AuditedEvent(), "audit", "true"));

        assertThat(selected).isTrue();
    }

    @Test
    void metadataPropertyWithValue_withDifferentPropertyValue_thenDoesNotSelectEvent() {
        var selector = metadataProperty("audit", "true");

        var selected = selector.selects(message(new AuditedEvent(), "audit", "false"));

        assertThat(selected).isFalse();
    }

    @Test
    void and_whenBothSelectorsSelectEvent_thenSelectsEvent() {
        var selector = eventType(AuditEvent.class).and(metadataProperty("audit", "true"));

        var selected = selector.selects(message(new AuditedEvent(), "audit", "true"));

        assertThat(selected).isTrue();
    }

    @Test
    void and_whenFirstSelectorDoesNotSelectEvent_thenShortCircuits() {
        EventSelector failingSelector = eventMessage -> {
            throw new AssertionError("Second selector should not be evaluated");
        };
        var selector = eventType(AuditEvent.class).and(failingSelector);

        var selected = selector.selects(message(new OtherEvent()));

        assertThat(selected).isFalse();
    }

    @Test
    void or_whenOneSelectorSelectsEvent_thenSelectsEvent() {
        var selector = eventType(AuditEvent.class).or(metadataProperty("audit"));

        var selected = selector.selects(message(new OtherEvent(), "audit", "true"));

        assertThat(selected).isTrue();
    }

    @Test
    void or_whenFirstSelectorSelectsEvent_thenShortCircuits() {
        EventSelector failingSelector = eventMessage -> {
            throw new AssertionError("Second selector should not be evaluated");
        };
        var selector = eventType(AuditEvent.class).or(failingSelector);

        var selected = selector.selects(message(new AuditedEvent()));

        assertThat(selected).isTrue();
    }

    @Test
    void negate_whenSelectorSelectsEvent_thenDoesNotSelectEvent() {
        var selector = eventType(AuditEvent.class).negate();

        var selected = selector.selects(message(new AuditedEvent()));

        assertThat(selected).isFalse();
    }

    @Test
    void eventType_withNullEventType_thenRejectsEventType() {
        assertThatNullPointerException()
            .isThrownBy(() -> eventType(null));
    }

    @Test
    void metadataProperty_withNullPropertyName_thenRejectsPropertyName() {
        assertThatNullPointerException()
            .isThrownBy(() -> metadataProperty(null));
    }

    @Test
    void metadataPropertyWithValue_withNullPropertyName_thenRejectsPropertyName() {
        assertThatNullPointerException()
            .isThrownBy(() -> metadataProperty(null, "true"));
    }

    @Test
    void metadataPropertyWithValue_withNullPropertyValue_thenRejectsPropertyValue() {
        assertThatNullPointerException()
            .isThrownBy(() -> metadataProperty("audit", null));
    }

    @Test
    void and_withNullSelector_thenRejectsSelector() {
        assertThatNullPointerException()
            .isThrownBy(() -> eventType(AuditEvent.class).and(null));
    }

    @Test
    void or_withNullSelector_thenRejectsSelector() {
        assertThatNullPointerException()
            .isThrownBy(() -> eventType(AuditEvent.class).or(null));
    }

    private static EventMessage<AuditedEvent> message(AuditedEvent event) {
        return EventMessage.from(event, METADATA);
    }

    private static EventMessage<OtherEvent> message(OtherEvent event) {
        return EventMessage.from(event, METADATA);
    }

    private static <E extends Event> EventMessage<E> message(E event, String propertyName, String propertyValue) {
        return EventMessage.from(event, METADATA.and(Metadata.withProperties(Map.of(propertyName, propertyValue))));
    }

    private interface AuditEvent extends Event {
    }

    private record AuditedEvent() implements AuditEvent {
    }

    private record OtherEvent() implements Event {
    }
}
