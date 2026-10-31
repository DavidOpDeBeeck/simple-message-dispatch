package app.dodb.smd.spring.eventstore.aggregate;

import app.dodb.smd.spring.EnableSMD;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD
public class AggregateTestConfiguration {

    @Bean
    public DepositLimit depositLimit() {
        return new DepositLimit(100);
    }
}
