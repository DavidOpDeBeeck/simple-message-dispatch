package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class EventIntegrationTestConfigurationWithEventStore {

    @Bean
    public TestEventHandler testEventHandler() {
        return new TestEventHandler();
    }

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer(EventStoreChannel eventStoreChannel) {
        return spec -> spec
            .processingGroup(ProcessingGroup.DEFAULT).channel(eventStoreChannel);
    }
}
