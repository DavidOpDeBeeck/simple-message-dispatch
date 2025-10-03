package app.dodb.smd.test;

import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.metadata.MetadataFactory;

public interface CommandBusConfigurer {

    CommandBus configure(MetadataFactory metadataFactory);
}
