package app.dodb.smd.api.metadata;

import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;

import java.time.Instant;
import java.util.UUID;

public class MetadataTestConstants {

    public static final UUID PRINCIPAL_ID = UUID.fromString("0ba66a2c-6693-48b5-a1fb-97d404fdac1c");
    public static final Principal PRINCIPAL = new SimplePrincipal(PRINCIPAL_ID);
    public static final Instant TIMESTAMP = Instant.now();
    public static final Metadata METADATA = new Metadata(PRINCIPAL, TIMESTAMP, null);
}
