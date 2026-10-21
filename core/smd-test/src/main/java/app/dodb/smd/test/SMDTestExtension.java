package app.dodb.smd.test;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.bus.QueryBus;
import app.dodb.smd.api.query.bus.QueryBusSpec;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class SMDTestExtension {

    private final CommandBusTestConfigurer commandBusTestConfigurer;
    private final QueryBusTestConfigurer queryBusTestConfigurer;
    private final EventBusTestConfigurer eventBusTestConfigurer;
    private final PrincipalProviderStub principalProviderStub;
    private final TimeProviderStub timeProviderStub;
    private final CommandGatewayStub commandGatewayStub;
    private final QueryGatewayStub queryGatewayStub;
    private final EventPublisherStub eventPublisherStub;
    private final DeadlineSchedulerStub deadlineSchedulerStub;

    public SMDTestExtension(CommandBusTestConfigurer commandBusTestConfigurer,
                            QueryBusTestConfigurer queryBusTestConfigurer,
                            EventBusTestConfigurer eventBusTestConfigurer,
                            PrincipalProviderStub principalProviderStub,
                            TimeProviderStub timeProviderStub,
                            CommandGatewayStub commandGatewayStub,
                            QueryGatewayStub queryGatewayStub,
                            EventPublisherStub eventPublisherStub,
                            DeadlineSchedulerStub deadlineSchedulerStub) {
        this.commandBusTestConfigurer = requireNonNull(commandBusTestConfigurer);
        this.queryBusTestConfigurer = requireNonNull(queryBusTestConfigurer);
        this.eventBusTestConfigurer = requireNonNull(eventBusTestConfigurer);
        this.principalProviderStub = requireNonNull(principalProviderStub);
        this.timeProviderStub = requireNonNull(timeProviderStub);
        this.commandGatewayStub = requireNonNull(commandGatewayStub);
        this.queryGatewayStub = requireNonNull(queryGatewayStub);
        this.eventPublisherStub = requireNonNull(eventPublisherStub);
        this.deadlineSchedulerStub = requireNonNull(deadlineSchedulerStub);
    }

    public void reset() {
        principalProviderStub.reset();
        timeProviderStub.reset();
        commandGatewayStub.reset();
        queryGatewayStub.reset();
        eventPublisherStub.reset();
        deadlineSchedulerStub.reset();
    }

    public SMDTestExtension stubPrincipal(Principal principal) {
        this.principalProviderStub.stubPrincipal(principal);
        return this;
    }

    public SMDTestExtension stubTimestamp(Instant timestamp) {
        this.timeProviderStub.stubTime(timestamp);
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

    public List<ScheduledDeadline> getDeadlines() {
        return deadlineSchedulerStub.getDeadlines();
    }

    public List<ScheduledDeadline> fireDueDeadlines() {
        return deadlineSchedulerStub.fireDue(configureCommandBus());
    }

    private CommandBus configureCommandBus() {
        var spec = CommandBusSpec.withoutDefaults()
            .time(timeProviderStub)
            .principal(principalProviderStub);
        return commandBusTestConfigurer.configure(spec);
    }

    private QueryBus configureQueryBus() {
        var spec = QueryBusSpec.withoutDefaults()
            .time(timeProviderStub)
            .principal(principalProviderStub);
        return queryBusTestConfigurer.configure(spec);
    }

    private EventBus configureEventBus() {
        var spec = EventBusSpec.withoutDefaults()
            .time(timeProviderStub)
            .principal(principalProviderStub);
        return eventBusTestConfigurer.configure(spec);
    }
}
