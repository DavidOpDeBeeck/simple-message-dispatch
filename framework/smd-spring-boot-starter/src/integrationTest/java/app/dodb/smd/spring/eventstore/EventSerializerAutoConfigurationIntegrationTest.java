package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.EventTypeResolver;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.StaticEventTypeResolver;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

import java.time.Instant;
import java.util.Map;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static tools.jackson.databind.SerializationFeature.INDENT_OUTPUT;

class EventSerializerAutoConfigurationIntegrationTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-04-22T10:15:30Z");

    @Test
    void createsJacksonEventSerializerWhenNoCustomEventSerializerBean() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            assertThat(fixture.bean(EventSerializer.class)).isInstanceOf(JacksonEventSerializer.class);
        }
    }

    @Test
    void usesCustomEventSerializerBean() {
        try (var fixture = eventStoreTestFixture()
            .configuration(CustomEventSerializerConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var serializer = fixture.bean(EventSerializer.class);

            assertThat(serializer).isInstanceOf(CustomEventSerializer.class);
            assertThat(fixture.beansOfType(EventSerializer.class)).hasSize(1);
            assertThat(fixture.bean(EventStoreConfig.class).getEventSerializer()).isSameAs(serializer);
        }
    }

    @Test
    void usesCustomEventTypeResolverBean() {
        try (var fixture = eventStoreTestFixture()
            .configuration(CustomEventTypeResolverConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var serializer = fixture.bean(EventSerializer.class);
            var eventMessage = eventMessage();

            var serialized = serializer.serialize(eventMessage);
            var deserialized = serializer.deserialize(serialized);

            assertThat(serialized.eventType()).isEqualTo(CustomEventTypeResolverConfiguration.EVENT_TYPE);
            assertThat(deserialized.payload()).isEqualTo(new SerializerTestEvent("value"));
        }
    }

    @Test
    void registersSmdJacksonModule() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var serializer = fixture.bean(EventSerializer.class);
            var principal = SimplePrincipal.create();
            var eventMessage = EventMessage.from(
                new SerializerTestEvent("value"),
                new Metadata(principal, TIMESTAMP, null)
            );

            var serialized = serializer.serialize(eventMessage);
            var deserialized = serializer.deserialize(serialized);

            assertThat(new String(serialized.serializedMetadata(), UTF_8))
                .contains("\"type\":\"" + SimplePrincipal.class.getName() + "\"");
            assertThat(deserialized.metadata().principal()).isEqualTo(principal);
        }
    }

    @Test
    void appliesJacksonModuleBeans() {
        try (var fixture = eventStoreTestFixture()
            .configuration(EventSerializerJacksonModuleConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var serialized = fixture.bean(EventSerializer.class).serialize(eventMessage());

            assertThat(new String(serialized.serializedPayload(), UTF_8))
                .contains("\"customValue\":\"value\"")
                .doesNotContain("\"value\":\"value\"");
        }
    }

    @Test
    void appliesJsonMapperBuilderCustomizerBeans() {
        try (var fixture = eventStoreTestFixture()
            .configuration(EventSerializerJsonMapperBuilderCustomizerConfiguration.class)
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var serialized = fixture.bean(EventSerializer.class).serialize(eventMessage());

            assertThat(new String(serialized.serializedPayload(), UTF_8)).contains("\n");
        }
    }

    private EventMessage<SerializerTestEvent> eventMessage() {
        return EventMessage.from(new SerializerTestEvent("value"), new Metadata(null, TIMESTAMP, null));
    }

    record SerializerTestEvent(@SubjectId String value) implements Event {
    }

    @Configuration
    static class CustomEventSerializerConfiguration {

        @Bean
        EventSerializer eventSerializer() {
            return new CustomEventSerializer();
        }
    }

    static class CustomEventSerializer implements EventSerializer {

        @Override
        public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
            throw new UnsupportedOperationException();
        }
    }

    @Configuration
    static class CustomEventTypeResolverConfiguration {

        static final String EVENT_TYPE = "custom-event";

        @Bean
        EventTypeResolver eventTypeResolver() {
            return new StaticEventTypeResolver(Map.of(EVENT_TYPE, SerializerTestEvent.class));
        }
    }

    @Configuration
    static class EventSerializerJacksonModuleConfiguration {

        @Bean
        JacksonModule eventSerializerJacksonModule() {
            return new SimpleModule("test-event-serializer-module")
                .setMixInAnnotation(SerializerTestEvent.class, SerializerTestEventMixin.class);
        }

        private interface SerializerTestEventMixin {

            @JsonProperty("customValue")
            String value();
        }
    }

    @Configuration
    static class EventSerializerJsonMapperBuilderCustomizerConfiguration {

        @Bean
        JsonMapperBuilderCustomizer eventSerializerJsonMapperBuilderCustomizer() {
            return builder -> builder.enable(INDENT_OUTPUT);
        }
    }
}
