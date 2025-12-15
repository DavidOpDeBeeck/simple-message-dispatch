package app.dodb.smd.api.metadata.principal;

import java.util.UUID;

public record SimplePrincipal(UUID id) implements Principal {

    public static SimplePrincipal create() {
        return new SimplePrincipal(UUID.randomUUID());
    }
}
