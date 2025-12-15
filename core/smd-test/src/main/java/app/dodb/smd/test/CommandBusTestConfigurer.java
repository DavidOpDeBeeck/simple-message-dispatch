package app.dodb.smd.test;

import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;

public interface CommandBusTestConfigurer {

    CommandBus configure(CommandBusSpec spec);
}
