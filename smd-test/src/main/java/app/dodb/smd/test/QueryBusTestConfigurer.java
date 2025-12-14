package app.dodb.smd.test;

import app.dodb.smd.api.query.bus.QueryBus;
import app.dodb.smd.api.query.bus.QueryBusSpec;

public interface QueryBusTestConfigurer {

    QueryBus configure(QueryBusSpec spec);
}
