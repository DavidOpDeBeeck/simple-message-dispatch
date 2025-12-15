package app.dodb.smd.api.query.bus;

import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandlerDispatcher;
import app.dodb.smd.api.query.QueryMessage;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class QueryBus implements QueryGateway {

    private final MetadataFactory metadataFactory;
    private final QueryHandlerDispatcher dispatcher;
    private final List<QueryBusInterceptor> interceptors;

    QueryBus(MetadataFactory metadataFactory,
             List<QueryBusInterceptor> interceptors,
             QueryHandlerDispatcher dispatcher) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.dispatcher = requireNonNull(dispatcher);
        this.interceptors = requireNonNull(interceptors);
    }

    @Override
    public <R, Q extends Query<R>> R send(Q query) {
        var chain = QueryBusInterceptorChain.<R, Q>create(dispatcher::dispatch, interceptors);
        return metadataFactory.createScope().run(metadata -> {
            return chain.proceed(QueryMessage.from(query, metadata));
        });
    }

    @Override
    public <R, Q extends Query<R>> R send(QueryMessage<R, Q> queryMessage) {
        var chain = QueryBusInterceptorChain.<R, Q>create(dispatcher::dispatch, interceptors);
        return metadataFactory.createScope(queryMessage.getMetadata()).run(metadata -> {
            return chain.proceed(queryMessage.withMetadata(metadata));
        });
    }
}
