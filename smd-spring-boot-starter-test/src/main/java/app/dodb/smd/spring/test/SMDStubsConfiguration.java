package app.dodb.smd.spring.test;

import app.dodb.smd.api.command.CommandHandlerDispatcher;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.event.EventHandlerDispatcher;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.query.QueryHandlerDispatcher;
import app.dodb.smd.api.query.bus.QueryBus;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.spring.SMDConfiguration;
import app.dodb.smd.spring.test.scope.SMDTestScopeConfiguration;
import app.dodb.smd.spring.test.scope.annotation.SMDTestScope;
import app.dodb.smd.test.CommandBusConfigurer;
import app.dodb.smd.test.CommandGatewayStub;
import app.dodb.smd.test.DatetimeProviderStub;
import app.dodb.smd.test.EventBusConfigurer;
import app.dodb.smd.test.EventPublisherStub;
import app.dodb.smd.test.PrincipalProviderStub;
import app.dodb.smd.test.QueryBusConfigurer;
import app.dodb.smd.test.QueryGatewayStub;
import app.dodb.smd.test.SMDTestExtension;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.util.List;

@ComponentScan
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
    public CommandBusConfigurer commandBusConfigurer(CommandHandlerDispatcher dispatcher, List<CommandBusInterceptor> interceptors) {
        return metadataContext -> new CommandBus(metadataContext, dispatcher, interceptors);
    }

    @Bean
    @SMDTestScope
    public QueryBusConfigurer queryBusConfigurer(QueryHandlerDispatcher dispatcher, List<QueryBusInterceptor> interceptors) {
        return metadataContext -> new QueryBus(metadataContext, dispatcher, interceptors);
    }

    @Bean
    @SMDTestScope
    public EventBusConfigurer eventBusConfigurer(EventHandlerDispatcher dispatcher, List<EventBusInterceptor> interceptors) {
        return metadataContext -> new EventBus(metadataContext, dispatcher, interceptors);
    }

    @Bean
    @SMDTestScope
    public SMDTestExtension smdTestExtension(
        CommandBusConfigurer commandBusConfigurer,
        QueryBusConfigurer queryBusConfigurer,
        EventBusConfigurer eventBusConfigurer,
        PrincipalProviderStub principalProviderStub,
        DatetimeProviderStub datetimeProviderStub,
        CommandGatewayStub commandGatewayStub,
        QueryGatewayStub queryGatewayStub,
        EventPublisherStub eventPublisherStub
    ) {
        return new SMDTestExtension(
            commandBusConfigurer,
            queryBusConfigurer,
            eventBusConfigurer,
            principalProviderStub,
            datetimeProviderStub,
            commandGatewayStub,
            queryGatewayStub,
            eventPublisherStub
        );
    }
}
