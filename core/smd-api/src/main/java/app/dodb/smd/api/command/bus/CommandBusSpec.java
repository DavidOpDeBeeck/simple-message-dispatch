package app.dodb.smd.api.command.bus;

import app.dodb.smd.api.command.CommandHandlerDispatcher;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.datetime.DatetimeProvider;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class CommandBusSpec {

    public static CommandBusSpec withDefaults() {
        return new CommandBusSpec()
            .datetime(new LocalDatetimeProvider())
            .principal(new SimplePrincipalProvider());
    }

    public static CommandBusSpec withoutDefaults() {
        return new CommandBusSpec();
    }

    private CommandBusSpec() {
    }

    private DatetimeProvider datetimeProvider;
    private PrincipalProvider principalProvider;
    private CommandHandlerDispatcher dispatcher;
    private final List<CommandBusInterceptor> interceptors = new ArrayList<>();

    public CommandBusSpec datetime(DatetimeProvider datetimeProvider) {
        this.datetimeProvider = requireNonNull(datetimeProvider);
        return this;
    }

    public CommandBusSpec principal(PrincipalProvider principalProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        return this;
    }

    public CommandBusSpec interceptors(CommandBusInterceptor... interceptors) {
        return interceptors(List.of(interceptors));
    }

    public CommandBusSpec interceptors(List<CommandBusInterceptor> interceptors) {
        this.interceptors.addAll(requireNonNull(interceptors));
        return this;
    }

    public CommandBusSpec commandHandlers(CommandHandlerLocator locator) {
        this.dispatcher = new CommandHandlerDispatcher(locator);
        return this;
    }

    public CommandBus create() {
        return new CommandBus(new MetadataFactory(principalProvider, datetimeProvider), interceptors, dispatcher);
    }
}
