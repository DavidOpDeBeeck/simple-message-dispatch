package app.dodb.smd.spring.query;

import app.dodb.smd.api.query.Query;

public record HelloQuery(String value) implements Query<String> {
}
