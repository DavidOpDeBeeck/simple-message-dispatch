package app.dodb.smd.spring.test;

import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.query.QueryHandlerLocator;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.spring.SMDConfiguration;
import app.dodb.smd.spring.test.scope.SMDTestScopeConfiguration;
import app.dodb.smd.spring.test.scope.annotation.SMDTestScope;
import app.dodb.smd.test.CommandBusTestConfigurer;
import app.dodb.smd.test.CommandGatewayStub;
import app.dodb.smd.test.DatetimeProviderStub;
import app.dodb.smd.test.EventBusTestConfigurer;
import app.dodb.smd.test.EventPublisherStub;
import app.dodb.smd.test.PrincipalProviderStub;
import app.dodb.smd.test.QueryBusTestConfigurer;
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
    public CommandBusTestConfigurer commandBusTestConfigurer(CommandHandlerLocator locator, List<CommandBusInterceptor> interceptors) {
        return spec -> spec
            .commandHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @SMDTestScope
    public QueryBusTestConfigurer queryBusTestConfigurer(QueryHandlerLocator locator, List<QueryBusInterceptor> interceptors) {
        return spec -> spec
            .queryHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @SMDTestScope
    public EventBusTestConfigurer eventBusTestConfigurer(ProcessingGroupLocator locator, ProcessingGroupsConfigurer processingGroupsConfigurer, List<EventBusInterceptor> interceptors) {
        return spec -> spec
            .processingGroups(locator, processingGroupsConfigurer)
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @SMDTestScope
    public SMDTestExtension smdTestExtension(
        CommandBusTestConfigurer commandBusTestConfigurer,
        QueryBusTestConfigurer queryBusTestConfigurer,
        EventBusTestConfigurer eventBusTestConfigurer,
        PrincipalProviderStub principalProviderStub,
        DatetimeProviderStub datetimeProviderStub,
        CommandGatewayStub commandGatewayStub,
        QueryGatewayStub queryGatewayStub,
        EventPublisherStub eventPublisherStub
    ) {
        return new SMDTestExtension(
            commandBusTestConfigurer,
            queryBusTestConfigurer,
            eventBusTestConfigurer,
            principalProviderStub,
            datetimeProviderStub,
            commandGatewayStub,
            queryGatewayStub,
            eventPublisherStub
        );
    }
}
