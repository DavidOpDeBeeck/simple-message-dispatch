package app.dodb.smd.spring.event;

import app.dodb.smd.spring.EnableSMD;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD
public class EventIntegrationTestConfigurationWithDefaults {

    @Bean
    public TestEventHandler testEventHandler() {
        return new TestEventHandler();
    }
}
