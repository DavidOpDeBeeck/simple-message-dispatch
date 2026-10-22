package app.dodb.smd.spring.deadline;

import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class DeadlineTestConfiguration {

    @Bean
    public ReminderHandler reminderHandler(DeadlineScheduler deadlineScheduler, DataSource dataSource) {
        return new ReminderHandler(deadlineScheduler, new JdbcTemplate(dataSource));
    }
}
