package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.metadata.principal.Principal;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.module.SimpleModule;

public class SMDJacksonModule extends SimpleModule {

    public SMDJacksonModule() {
        super("smd-jackson-module");
        setMixInAnnotation(Principal.class, PrincipalMixin.class);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "type")
    private interface PrincipalMixin {
    }
}
