package app.dodb.smd.spring;

import app.dodb.smd.api.command.bus.CommandBusSpec;

@FunctionalInterface
public interface CommandBusSpecCustomizer {

    void customize(CommandBusSpec spec);
}
