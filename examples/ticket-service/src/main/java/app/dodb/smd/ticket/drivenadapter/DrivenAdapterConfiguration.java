package app.dodb.smd.ticket.drivenadapter;

import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.durabledelivery.DurableDelivery;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.serialization.EventTypeResolver;
import app.dodb.smd.serialization.StaticEventTypeResolver;
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

    public static final String TICKET_VIEW = "ticket-view";
    public static final String TICKET_LOG = "ticket-log";
    public static final String TICKET_ACTIVITY = "ticket-activity";
    public static final String CUSTOMER_WEBHOOKS = "customer-webhooks";

    @Bean
    @ConditionalOnMissingBean(name = "drivenAdapterProcessingGroups")
    public ProcessingGroupsConfigurer drivenAdapterProcessingGroups(DurableDelivery durableDelivery, EventStore eventStore) {
        return spec -> spec
            .processingGroup(TICKET_VIEW).sync()
            .processingGroup(TICKET_LOG).async().await()
            .processingGroup(TICKET_ACTIVITY).source(eventStore.outlet())
            .processingGroup(CUSTOMER_WEBHOOKS).medium(durableDelivery.dispatcher("ticket-service." + CUSTOMER_WEBHOOKS));
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
