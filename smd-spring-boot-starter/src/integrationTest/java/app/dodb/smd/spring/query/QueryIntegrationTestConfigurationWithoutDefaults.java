package app.dodb.smd.spring.query;

import app.dodb.smd.spring.EnableSMD;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD(packages = "app.dodb.smd.spring.query")
public class QueryIntegrationTestConfigurationWithoutDefaults {

    @Bean
    public HelloQueryHandler helloQueryHandler() {
        return new HelloQueryHandler();
    }
}
