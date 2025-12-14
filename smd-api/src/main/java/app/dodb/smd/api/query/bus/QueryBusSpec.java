package app.dodb.smd.api.query.bus;

import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.datetime.DatetimeProvider;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.query.QueryHandlerDispatcher;
import app.dodb.smd.api.query.QueryHandlerLocator;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class QueryBusSpec {

    public static QueryBusSpec withDefaults() {
        return new QueryBusSpec()
            .datetime(new LocalDatetimeProvider())
            .principal(new SimplePrincipalProvider());
    }

    public static QueryBusSpec withoutDefaults() {
        return new QueryBusSpec();
    }

    private QueryBusSpec() {
    }

    private DatetimeProvider datetimeProvider;
    private PrincipalProvider principalProvider;
    private QueryHandlerDispatcher dispatcher;
    private final List<QueryBusInterceptor> interceptors = new ArrayList<>();

    public QueryBusSpec datetime(DatetimeProvider datetimeProvider) {
        this.datetimeProvider = requireNonNull(datetimeProvider);
        return this;
    }

    public QueryBusSpec principal(PrincipalProvider principalProvider) {
        this.principalProvider = requireNonNull(principalProvider);
        return this;
    }

    public QueryBusSpec interceptors(QueryBusInterceptor... interceptors) {
        return interceptors(List.of(interceptors));
    }

    public QueryBusSpec interceptors(List<QueryBusInterceptor> interceptors) {
        this.interceptors.addAll(requireNonNull(interceptors));
        return this;
    }

    public QueryBusSpec queryHandlers(QueryHandlerLocator locator) {
        this.dispatcher = new QueryHandlerDispatcher(locator);
        return this;
    }

    public QueryBus create() {
        return new QueryBus(new MetadataFactory(principalProvider, datetimeProvider), interceptors, dispatcher);
    }
}
