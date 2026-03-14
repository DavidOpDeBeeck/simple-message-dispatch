package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD
public class EventIntegrationTestConfigurationWithAsyncAwait {

    @Bean
    public TestEventHandler testEventHandler() {
        return new TestEventHandler();
    }

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer() {
        return spec -> spec
            .processingGroup(ProcessingGroup.DEFAULT).async().await();
    }
}
