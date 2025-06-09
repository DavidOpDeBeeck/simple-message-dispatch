package app.dodb.smd.spring;

import app.dodb.smd.api.command.CommandBus;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandlerDispatcher;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.PackageBasedCommandHandlerLocator;
import app.dodb.smd.api.event.EventBus;
import app.dodb.smd.api.event.EventHandlerDispatcher;
import app.dodb.smd.api.event.EventHandlerLocator;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.PackageBasedEventHandlerLocator;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.metadata.DatetimeProvider;
import app.dodb.smd.api.metadata.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.PrincipalProvider;
import app.dodb.smd.api.metadata.PrincipalProviderImpl;
import app.dodb.smd.api.query.PackageBasedQueryHandlerLocator;
import app.dodb.smd.api.query.QueryBus;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandlerDispatcher;
import app.dodb.smd.api.query.QueryHandlerLocator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class SMDConfiguration {

    @Bean
    public ObjectCreator objectCreator(ApplicationContext applicationContext) {
        return new SpringObjectCreator(applicationContext);
    }

    @Bean
    public PrincipalProvider principalProvider() {
        return new PrincipalProviderImpl();
    }

    @Bean
    public DatetimeProvider datetimeProvider() {
        return new LocalDatetimeProvider();
    }

    @Bean
    public MetadataFactory metadataFactory(PrincipalProvider principalProvider, DatetimeProvider datetimeProvider) {
        return new MetadataFactory(principalProvider, datetimeProvider);
    }

    @Bean
    public CommandHandlerLocator commandHandlerLocator(SMDProperties properties, ObjectCreator objectCreator) {
        return new PackageBasedCommandHandlerLocator(properties.packages(), objectCreator);
    }

    @Bean
    public EventHandlerLocator eventHandlerLocator(SMDProperties properties, ObjectCreator objectCreator) {
        return new PackageBasedEventHandlerLocator(properties.packages(), objectCreator);
    }

    @Bean
    public QueryHandlerLocator queryHandlerLocator(SMDProperties properties, ObjectCreator objectCreator) {
        return new PackageBasedQueryHandlerLocator(properties.packages(), objectCreator);
    }

    @Bean
    public CommandHandlerDispatcher commandHandlerDispatcher(CommandHandlerLocator locator) {
        return new CommandHandlerDispatcher(locator);
    }

    @Bean
    public EventHandlerDispatcher eventHandlerDispatcher(EventHandlerLocator locator) {
        return new EventHandlerDispatcher(locator);
    }

    @Bean
    public QueryHandlerDispatcher queryHandlerDispatcher(QueryHandlerLocator locator) {
        return new QueryHandlerDispatcher(locator);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandGateway commandGateway(MetadataFactory metadataFactory, CommandHandlerDispatcher dispatcher) {
        return new CommandBus(metadataFactory, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher eventPublisher(MetadataFactory metadataFactory, EventHandlerDispatcher dispatcher) {
        return new EventBus(metadataFactory, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryGateway queryGateway(MetadataFactory metadataFactory, QueryHandlerDispatcher dispatcher) {
        return new QueryBus(metadataFactory, dispatcher);
    }
}
