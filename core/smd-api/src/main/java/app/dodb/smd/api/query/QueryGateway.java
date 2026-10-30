package app.dodb.smd.api.query;

import app.dodb.smd.api.metadata.Metadata;

import java.util.function.UnaryOperator;

public interface QueryGateway {

    <R, Q extends Query<R>> R send(Q query);

    <R, Q extends Query<R>> R send(Q query, Metadata metadata);

    <R, Q extends Query<R>> R send(Q query, UnaryOperator<Metadata> metadataUpdate);

    <R, Q extends Query<R>> R send(QueryMessage<R, Q> queryMessage);
}
