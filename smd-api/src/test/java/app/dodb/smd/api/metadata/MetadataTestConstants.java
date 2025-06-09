package app.dodb.smd.api.metadata;

import java.time.LocalDateTime;

public class MetadataTestConstants {

    public static final Principal PRINCIPAL = new Principal();
    public static final LocalDateTime TIMESTAMP = LocalDateTime.now();
    public static final Metadata METADATA = new Metadata(PRINCIPAL, TIMESTAMP);
}
