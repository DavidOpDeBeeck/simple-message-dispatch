package app.dodb.smd.spring;

import app.dodb.smd.api.query.bus.QueryBusSpec;

@FunctionalInterface
public interface QueryBusSpecCustomizer {

    void customize(QueryBusSpec spec);
}
