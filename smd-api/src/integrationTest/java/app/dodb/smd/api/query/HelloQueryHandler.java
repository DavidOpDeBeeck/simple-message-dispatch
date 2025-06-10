package app.dodb.smd.api.query;

import java.util.ArrayList;
import java.util.List;

public class HelloQueryHandler {

    static List<Query<?>> handledQueries = new ArrayList<>();

    @QueryHandler
    public String handle(HelloQuery query) {
        handledQueries.add(query);
        return "Hello " + query.value();
    }
}
