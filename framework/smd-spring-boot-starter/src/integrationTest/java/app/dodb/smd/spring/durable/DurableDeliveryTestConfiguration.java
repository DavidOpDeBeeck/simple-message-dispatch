package app.dodb.smd.spring.durable;

import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableAutoConfiguration
@EnableSMD(packages = "app.dodb.smd.spring.durable.nohandlers")
public class DurableDeliveryTestConfiguration {

    @Bean
    public EventSerializer eventSerializer() {
        return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
    }
}
