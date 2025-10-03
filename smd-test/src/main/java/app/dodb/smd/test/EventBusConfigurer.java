package app.dodb.smd.test;

import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.metadata.MetadataFactory;

public interface EventBusConfigurer {

    EventBus configure(MetadataFactory metadataFactory);
}
