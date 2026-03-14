package app.dodb.smd.api.metadata.datetime;

import java.time.Instant;

public interface TimeProvider {

    Instant now();
}
