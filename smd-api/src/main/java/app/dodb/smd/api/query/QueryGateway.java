package app.dodb.smd.api.query;

public interface QueryGateway {

    <R, Q extends Query<R>> R send(Q query);

    <R, Q extends Query<R>> R send(QueryMessage<R, Q> queryMessage);
}
