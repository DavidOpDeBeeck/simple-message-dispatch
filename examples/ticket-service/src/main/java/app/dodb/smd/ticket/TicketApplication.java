package app.dodb.smd.ticket;

import app.dodb.smd.ticket.drivenadapter.DrivenAdapterConfiguration;
import app.dodb.smd.ticket.drivingadapter.DrivingAdapterConfiguration;
import app.dodb.smd.ticket.infrastructure.InfrastructureConfiguration;
import app.dodb.smd.ticket.usecase.UseCaseConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
    DrivenAdapterConfiguration.class,
    DrivingAdapterConfiguration.class,
    InfrastructureConfiguration.class,
    UseCaseConfiguration.class
})
public class TicketApplication {

    static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
