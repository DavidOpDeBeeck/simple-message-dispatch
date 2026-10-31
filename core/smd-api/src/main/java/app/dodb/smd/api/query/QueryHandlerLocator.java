package app.dodb.smd.api.query;

import java.util.List;

public interface QueryHandlerLocator {

    QueryHandlerRegistry locate();

    static QueryHandlerLocator combine(List<QueryHandlerLocator> locators) {
        return () -> locators.stream()
            .map(QueryHandlerLocator::locate)
            .reduce(QueryHandlerRegistry::and)
            .orElse(QueryHandlerRegistry.empty());
    }
}
