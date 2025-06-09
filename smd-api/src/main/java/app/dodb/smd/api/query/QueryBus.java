package app.dodb.smd.api.query;

import app.dodb.smd.api.metadata.MetadataFactory;

import static java.util.Objects.requireNonNull;

public class QueryBus implements QueryGateway {

    private final MetadataFactory metadataFactory;
    private final QueryHandlerDispatcher dispatcher;

    public QueryBus(MetadataFactory metadataFactory,
                    QueryHandlerDispatcher dispatcher) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.dispatcher = requireNonNull(dispatcher);
    }

    @Override
    public <R, Q extends Query<R>> R send(Q query) {
        return send(QueryMessage.from(query, metadataFactory.create()));
    }

    @Override
    public <R, Q extends Query<R>> R send(QueryMessage<R, Q> queryMessage) {
        return dispatcher.dispatch(queryMessage);
    }
}
