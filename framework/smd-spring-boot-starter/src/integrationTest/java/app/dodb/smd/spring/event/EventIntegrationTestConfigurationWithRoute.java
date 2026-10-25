package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.event.delivery.EventRoute;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSMD
public class EventIntegrationTestConfigurationWithRoute {

    @Bean
    public TestEventHandler testEventHandler() {
        return new TestEventHandler();
    }

    @Bean
    public SynchronousEventDispatcher outgoing() {
        return new SynchronousEventDispatcher();
    }

    @Bean
    public SynchronousEventDispatcher incoming() {
        return new SynchronousEventDispatcher();
    }

    @Bean
    public EventBusSpecCustomizer outgoingSink(@Qualifier("outgoing") SynchronousEventDispatcher outgoing) {
        return spec -> spec.sinks(outgoing.inlet());
    }

    @Bean
    public EventRoute outgoingToIncoming(@Qualifier("outgoing") SynchronousEventDispatcher outgoing, @Qualifier("incoming") SynchronousEventDispatcher incoming) {
        return new EventRoute("outgoing-to-incoming", outgoing.outlet(), incoming.inlet());
    }

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer(@Qualifier("incoming") SynchronousEventDispatcher incoming) {
        return groups -> groups.anyProcessingGroup().source(incoming.outlet());
    }
}
