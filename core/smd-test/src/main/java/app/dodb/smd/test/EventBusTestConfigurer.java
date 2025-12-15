package app.dodb.smd.test;

import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusSpec;

public interface EventBusTestConfigurer {

    EventBus configure(EventBusSpec spec);
}
