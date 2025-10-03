package app.dodb.smd.test;

import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.query.bus.QueryBus;

public interface QueryBusConfigurer {

    QueryBus configure(MetadataFactory metadataFactory);
}
