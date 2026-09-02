package app.dodb.smd.spring;

import app.dodb.smd.api.event.bus.EventBusSpec;

@FunctionalInterface
public interface EventBusSpecCustomizer {

    void customize(EventBusSpec spec);
}
