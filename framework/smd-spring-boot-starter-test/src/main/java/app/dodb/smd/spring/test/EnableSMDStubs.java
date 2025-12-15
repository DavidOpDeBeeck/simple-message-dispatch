package app.dodb.smd.spring.test;

import app.dodb.smd.spring.test.scope.SMDTestScopeLifecycleExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(TYPE)
@Retention(RUNTIME)
@ExtendWith(SMDTestScopeLifecycleExtension.class)
@ImportAutoConfiguration(SMDStubsConfiguration.class)
public @interface EnableSMDStubs {
}