package app.dodb.smd.spring.command;

import app.dodb.smd.spring.EnableSMD;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD(packages = "app.dodb.smd.spring.command")
public class CommandIntegrationTestConfigurationWithoutDefaults {

    @Bean
    public IncrementCommandHandler incrementCommandHandler() {
        return new IncrementCommandHandler();
    }
}
