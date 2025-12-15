package app.dodb.smd.api.query;

public interface QueryHandlerBehaviour<R, Q extends Query<R>> {

    Class<Q> queryType();

    R handle(QueryMessage<R, Q> queryMessage);
}
