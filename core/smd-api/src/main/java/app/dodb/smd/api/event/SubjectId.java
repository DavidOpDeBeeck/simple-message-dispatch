package app.dodb.smd.api.event;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Target({RECORD_COMPONENT, METHOD})
@Retention(RUNTIME)
public @interface SubjectId {
}
