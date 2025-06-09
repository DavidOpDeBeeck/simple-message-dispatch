package app.dodb.smd.test;

import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryMessage;

import java.util.HashMap;
import java.util.Map;

public class QueryGatewayStub implements QueryGateway {

    private final Map<Query<?>, Object> responseByQuery = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <R, Q extends Query<R>> R send(Q query) {
        return (R) responseByQuery.get(query);
    }

    @Override
    public <R, Q extends Query<R>> R send(QueryMessage<R, Q> queryMessage) {
        return send(queryMessage.getPayload());
    }

    public <R, Q extends Query<R>> void stubQuery(Q query, R response) {
        responseByQuery.put(query, response);
    }

    public void reset() {
        responseByQuery.clear();
    }
}
