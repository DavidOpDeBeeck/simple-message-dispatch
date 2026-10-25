package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class AggregateTestConfiguration {

    @Bean
    public BankAccountView bankAccountView() {
        return new BankAccountView();
    }

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer(EventStore eventStore) {
        return groups -> groups.processingGroup(BankAccountView.PROCESSING_GROUP).source(eventStore.outlet());
    }
}
