package app.dodb.smd.api.metadata.time;

import java.time.Instant;

public class SystemTimeProvider implements TimeProvider {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
