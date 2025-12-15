package app.dodb.smd.spring;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.PackageBasedCommandHandlerLocator;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.PackageBasedProcessingGroupLocator;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.metadata.datetime.DatetimeProvider;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.query.PackageBasedQueryHandlerLocator;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandlerLocator;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;

import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.multi;

@AutoConfiguration
public class SMDConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectCreator objectCreator(ApplicationContext applicationContext) {
        return new SpringObjectCreator(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public PrincipalProvider simplePrincipalProvider() {
        return new SimplePrincipalProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public DatetimeProvider localDatetimeProvider() {
        return new LocalDatetimeProvider();
    }

    @Bean
    public CommandHandlerLocator commandHandlerLocator(List<SMDProperties> properties, ObjectCreator objectCreator) {
        List<String> packages = combinePackages(properties);
        return new PackageBasedCommandHandlerLocator(packages, objectCreator);
    }

    @Bean
    public ProcessingGroupLocator processingGroupLocator(List<SMDProperties> properties, ObjectCreator objectCreator) {
        List<String> packages = combinePackages(properties);
        return new PackageBasedProcessingGroupLocator(packages, objectCreator);
    }

    @Bean
    public QueryHandlerLocator queryHandlerLocator(List<SMDProperties> properties, ObjectCreator objectCreator) {
        List<String> packages = combinePackages(properties);
        return new PackageBasedQueryHandlerLocator(packages, objectCreator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessingGroupsConfigurer defaultProcessingGroupsConfigurer() {
        return spec -> spec.anyProcessingGroup().blocking();
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandGateway commandGateway(DatetimeProvider datetimeProvider,
                                         PrincipalProvider principalProvider,
                                         CommandHandlerLocator locator,
                                         List<CommandBusInterceptor> interceptors) {
        return CommandBusSpec.withoutDefaults()
            .datetime(datetimeProvider)
            .principal(principalProvider)
            .commandHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(DatetimeProvider datetimeProvider,
                                         PrincipalProvider principalProvider,
                                         ProcessingGroupLocator locator,
                                         List<ProcessingGroupsConfigurer> processingGroupsConfigurers,
                                         List<EventBusInterceptor> interceptors) {
        return EventBusSpec.withoutDefaults()
            .datetime(datetimeProvider)
            .principal(principalProvider)
            .processingGroups(locator, multi(processingGroupsConfigurers))
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryGateway queryGateway(DatetimeProvider datetimeProvider,
                                     PrincipalProvider principalProvider,
                                     QueryHandlerLocator locator,
                                     List<QueryBusInterceptor> interceptors) {
        return QueryBusSpec.withoutDefaults()
            .datetime(datetimeProvider)
            .principal(principalProvider)
            .queryHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    private static List<String> combinePackages(List<SMDProperties> properties) {
        return properties.stream()
            .map(SMDProperties::packages)
            .flatMap(List::stream)
            .toList();
    }
}
