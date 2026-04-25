package app.dodb.smd.spring;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.PackageBasedCommandHandlerLocator;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.command.bus.TransactionalCommandBusInterceptor;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.PackageBasedProcessingGroupLocator;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.TransactionalEventInterceptor;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.api.query.PackageBasedQueryHandlerLocator;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandlerLocator;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import app.dodb.smd.api.query.bus.TransactionalQueryBusInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import java.util.List;

import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.defaultSynchronous;
import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.multi;
import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

@AutoConfiguration
public class SMDAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectCreator objectCreator(ApplicationContext applicationContext) {
        return new SpringObjectCreator(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public PrincipalProvider principalProvider() {
        return new SimplePrincipalProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeProvider timeProvider() {
        return new SystemTimeProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionProvider transactionProvider() {
        return new SpringTransactionProvider();
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
    public ProcessingGroupsConfigurer processingGroupsConfigurer() {
        return defaultSynchronous();
    }

    @Bean
    @Order(HIGHEST_PRECEDENCE)
    public CommandBusInterceptor transactionalCommandBusInterceptor(TransactionProvider transactionProvider) {
        return new TransactionalCommandBusInterceptor(transactionProvider);
    }

    @Bean
    @Order(HIGHEST_PRECEDENCE)
    public EventInterceptor transactionalEventBusInterceptor(TransactionProvider transactionProvider) {
        return new TransactionalEventInterceptor(transactionProvider);
    }

    @Bean
    @Order(HIGHEST_PRECEDENCE)
    public QueryBusInterceptor transactionalQueryBusInterceptor(TransactionProvider transactionProvider) {
        return new TransactionalQueryBusInterceptor(transactionProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandGateway commandGateway(TimeProvider timeProvider,
                                         PrincipalProvider principalProvider,
                                         CommandHandlerLocator locator,
                                         List<CommandBusInterceptor> interceptors) {
        return CommandBusSpec.withoutDefaults()
            .time(timeProvider)
            .principal(principalProvider)
            .commandHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(TimeProvider timeProvider,
                                         PrincipalProvider principalProvider,
                                         ProcessingGroupLocator locator,
                                         List<ProcessingGroupsConfigurer> processingGroupsConfigurers,
                                         List<EventInterceptor> interceptors) {
        return EventBusSpec.withoutDefaults()
            .time(timeProvider)
            .principal(principalProvider)
            .processingGroups(locator, multi(processingGroupsConfigurers))
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryGateway queryGateway(TimeProvider timeProvider,
                                     PrincipalProvider principalProvider,
                                     QueryHandlerLocator locator,
                                     List<QueryBusInterceptor> interceptors) {
        return QueryBusSpec.withoutDefaults()
            .time(timeProvider)
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
