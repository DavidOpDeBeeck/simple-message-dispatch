package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.query.Query;

public record GetAccountBalanceQuery() implements Query<Integer> {
}
