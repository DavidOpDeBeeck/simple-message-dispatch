package app.dodb.smd.spring.event.processing;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD
public class EventStoreChannelProcessingTestConfiguration {

    @Bean
    public FailableTestEventHandler failableTestEventHandler() {
        return new FailableTestEventHandler();
    }

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer(EventStoreChannel eventStoreChannel) {
        return spec -> spec
            .processingGroup(ProcessingGroup.DEFAULT).channel(eventStoreChannel);
    }
}
