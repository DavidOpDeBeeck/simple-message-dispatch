package app.dodb.smd.spring.durable.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.durable.DurableDelivery;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.StaticEventTypeResolver;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class DurableDeliveryTestConfiguration {

    public static final String ORDERS = "orders";
    public static final Map<String, Class<? extends Event>> EVENT_TYPES = Map.of(
        "order.v1", OrderEvent.class,
        "note.v1", NoteEvent.class
    );

    public static EventSerializer eventSerializerFor(Map<String, Class<? extends Event>> eventTypes) {
        return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build(), new StaticEventTypeResolver(eventTypes), List.of());
    }

    @Bean
    public EventSerializer eventSerializer() {
        return eventSerializerFor(EVENT_TYPES);
    }

    @Bean
    public OrderEventHandler orderEventHandler() {
        return new OrderEventHandler();
    }

    @Bean
    public ProcessingGroupsConfigurer orderProcessingGroups(DurableDelivery durableDelivery) {
        return spec -> spec
            .processingGroup(ORDERS).medium(durableDelivery.dispatcher(ORDERS));
    }
}
