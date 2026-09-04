package app.dodb.smd.ticket.drivenadapter;

import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.serialization.EventTypeResolver;
import app.dodb.smd.eventstore.serialization.StaticEventTypeResolver;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@EnableSMD
@Configuration
@ComponentScan
public class DrivenAdapterConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "ticketProcessingGroups")
    public ProcessingGroupsConfigurer ticketProcessingGroups(EventStore eventStore) {
        return spec -> spec
            .processingGroup("ticket-view").sync()
            .processingGroup("notifications").async().await()
            .processingGroup("ticket-activity").source(eventStore.outlet());
    }

    @Bean
    public EventTypeResolver ticketEventTypeResolver() {
        return new StaticEventTypeResolver(Map.of(
            "ticket.opened.v1", TicketOpenedEvent.class,
            "ticket.assigned.v1", TicketAssignedEvent.class,
            "ticket.resolved.v1", TicketResolvedEvent.class
        ));
    }
}
