package app.dodb.smd.spring.eventstore.processing;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.eventstore.channel.EventStore;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class EventStoreProcessingTestConfiguration {

    @Bean
    public FailableTestEventHandler failableTestEventHandler() {
        return new FailableTestEventHandler();
    }

    @Bean
    public SideEffectFailingTestEventHandler sideEffectFailingTestEventHandler(DataSource dataSource) {
        return new SideEffectFailingTestEventHandler(dataSource);
    }

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer(EventStore eventStore) {
        return spec -> spec
            .processingGroup(ProcessingGroup.DEFAULT).source(eventStore);
    }
}
