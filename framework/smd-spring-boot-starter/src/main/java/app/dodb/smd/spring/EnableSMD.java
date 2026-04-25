package app.dodb.smd.spring;

import app.dodb.smd.spring.eventstore.SMDEventStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(TYPE)
@Retention(RUNTIME)
@Import(SMDRegistrar.class)
@ImportAutoConfiguration({
    SMDAutoConfiguration.class,
    SMDEventStoreAutoConfiguration.class,
})
public @interface EnableSMD {

    String[] packages() default {};
}
