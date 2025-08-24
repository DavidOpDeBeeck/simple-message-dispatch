package app.dodb.smd.spring.test;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.test.example.MoneyTransferProcessManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD
public class IntegrationTestConfiguration {

    @Bean
    public MoneyTransferProcessManager handlerForTest(CommandGateway commandGateway, EventPublisher eventPublisher) {
        return new MoneyTransferProcessManager(commandGateway, eventPublisher);
    }
}
