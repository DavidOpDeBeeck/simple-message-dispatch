package app.dodb.smd.ticket.drivenadapter;


import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(TYPE)
@Retention(RUNTIME)
@EnableAutoConfiguration
@SpringBootTest(classes = DrivenAdapterConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
public @interface IntegrationTest {
}
