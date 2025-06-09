package app.dodb.smd.spring.test;

import app.dodb.smd.api.command.CommandHandlerDispatcher;
import app.dodb.smd.api.event.EventHandlerDispatcher;
import app.dodb.smd.api.query.QueryHandlerDispatcher;
import app.dodb.smd.spring.SMDConfiguration;
import app.dodb.smd.spring.test.scope.SMDTestScopeConfiguration;
import app.dodb.smd.spring.test.scope.annotation.SMDTestScope;
import app.dodb.smd.test.CommandGatewayStub;
import app.dodb.smd.test.EventPublisherStub;
import app.dodb.smd.test.QueryGatewayStub;
import app.dodb.smd.test.SMDTestExtension;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@AutoConfigureBefore(SMDConfiguration.class)
@Import(SMDTestScopeConfiguration.class)
public class SMDStubsConfiguration {

    @Bean
    @SMDTestScope
    public CommandGatewayStub commandGatewayStub() {
        return new CommandGatewayStub();
    }

    @Bean
    @SMDTestScope
    public EventPublisherStub eventPublisherStub() {
        return new EventPublisherStub();
    }

    @Bean
    @SMDTestScope
    public QueryGatewayStub queryGatewayStub() {
        return new QueryGatewayStub();
    }

    @Bean
    @SMDTestScope
    public SMDTestExtension simpleMessageDispatchTestFixture(
        CommandHandlerDispatcher commandHandlerDispatcher,
        QueryHandlerDispatcher queryHandlerDispatcher,
        EventHandlerDispatcher eventHandlerDispatcher,
        CommandGatewayStub commandGatewayStub,
        QueryGatewayStub queryGatewayStub,
        EventPublisherStub eventPublisherStub
    ) {
        return new SMDTestExtension(
            commandHandlerDispatcher,
            queryHandlerDispatcher,
            eventHandlerDispatcher,
            commandGatewayStub,
            queryGatewayStub,
            eventPublisherStub
        );
    }
}
