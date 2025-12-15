package app.dodb.smd.spring.query;

import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryHandler;

import java.util.ArrayList;
import java.util.List;

public class HelloQueryHandler {

    private final List<Query<?>> handledQueries = new ArrayList<>();

    @QueryHandler
    public String handle(HelloQuery query) {
        handledQueries.add(query);
        return "Hello " + query.value();
    }

    public List<Query<?>> getHandledQueries() {
        return handledQueries;
    }
}
