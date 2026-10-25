package app.dodb.smd.spring.serialization;

import app.dodb.smd.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.EventTypeResolver;
import app.dodb.smd.serialization.jackson.EventUpcaster;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static tools.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;

@Configuration(proxyBeanMethods = false)
public class SMDEventSerializationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventTypeResolver eventTypeResolver() {
        return new ClassNameEventTypeResolver();
    }

    @Bean
    @ConditionalOnBean(JsonMapper.Builder.class)
    @ConditionalOnMissingBean(EventSerializer.class)
    public EventSerializer eventSerializer(JsonMapper.Builder jsonMapperBuilder, EventTypeResolver eventTypeResolver, List<EventUpcaster> eventUpcasters) {
        var objectMapper = jsonMapperBuilder
            .disable(FAIL_ON_EMPTY_BEANS)
            .addModule(new SMDJacksonModule())
            .build();
        return new JacksonEventSerializer(objectMapper, eventTypeResolver, eventUpcasters);
    }
}
