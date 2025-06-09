package app.dodb.smd.test;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandBus;
import app.dodb.smd.api.command.CommandHandlerDispatcher;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventBus;
import app.dodb.smd.api.event.EventHandlerDispatcher;
import app.dodb.smd.api.metadata.DatetimeProvider;
import app.dodb.smd.api.metadata.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.Principal;
import app.dodb.smd.api.metadata.PrincipalProvider;
import app.dodb.smd.api.metadata.PrincipalProviderImpl;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryBus;
import app.dodb.smd.api.query.QueryHandlerDispatcher;

import java.time.LocalDateTime;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class SMDTestExtension {

    private DatetimeProvider datetimeProvider = new LocalDatetimeProvider();
    private PrincipalProvider principalProvider = new PrincipalProviderImpl();

    private final CommandHandlerDispatcher commandHandlerDispatcher;
    private final QueryHandlerDispatcher queryHandlerDispatcher;
    private final EventHandlerDispatcher eventHandlerDispatcher;
    private final CommandGatewayStub commandGatewayStub;
    private final QueryGatewayStub queryGatewayStub;
    private final EventPublisherStub eventPublisherStub;

    public SMDTestExtension(CommandHandlerDispatcher commandHandlerDispatcher,
                            QueryHandlerDispatcher queryHandlerDispatcher,
                            EventHandlerDispatcher eventHandlerDispatcher,
                            CommandGatewayStub commandGatewayStub,
                            QueryGatewayStub queryGatewayStub,
                            EventPublisherStub eventPublisherStub) {
        this.commandHandlerDispatcher = requireNonNull(commandHandlerDispatcher);
        this.queryHandlerDispatcher = requireNonNull(queryHandlerDispatcher);
        this.eventHandlerDispatcher = requireNonNull(eventHandlerDispatcher);
        this.commandGatewayStub = requireNonNull(commandGatewayStub);
        this.queryGatewayStub = requireNonNull(queryGatewayStub);
        this.eventPublisherStub = requireNonNull(eventPublisherStub);
    }

    public void reset() {
        commandGatewayStub.reset();
        queryGatewayStub.reset();
        eventPublisherStub.reset();
    }

    public SMDTestExtension stubPrincipal(Principal principal) {
        this.principalProvider = () -> principal;
        return this;
    }

    public SMDTestExtension stubTimestamp(LocalDateTime timestamp) {
        this.datetimeProvider = () -> timestamp;
        return this;
    }

    public <R, C extends Command<R>> SMDTestExtension stubCommand(C command, R response) {
        commandGatewayStub.stubCommand(command, response);
        return this;
    }

    public <R, Q extends Query<R>> SMDTestExtension stubQuery(Q query, R response) {
        queryGatewayStub.stubQuery(query, response);
        return this;
    }

    public <R, C extends Command<R>> R send(C command) {
        CommandBus commandBus = configureCommandBus();
        return commandBus.send(command);
    }

    public <R, Q extends Query<R>> R send(Q query) {
        QueryBus queryBus = configureQueryBus();
        return queryBus.send(query);
    }

    public <E extends Event> void send(E event) {
        EventBus eventBus = configureEventBus();
        eventBus.publish(event);
    }

    public List<Event> getEvents() {
        return eventPublisherStub.getEvents();
    }

    private CommandBus configureCommandBus() {
        return new CommandBus(
            new MetadataFactory(principalProvider, datetimeProvider),
            commandHandlerDispatcher
        );
    }

    private QueryBus configureQueryBus() {
        return new QueryBus(
            new MetadataFactory(principalProvider, datetimeProvider),
            queryHandlerDispatcher
        );
    }

    private EventBus configureEventBus() {
        return new EventBus(
            new MetadataFactory(principalProvider, datetimeProvider),
            eventHandlerDispatcher
        );
    }
}
