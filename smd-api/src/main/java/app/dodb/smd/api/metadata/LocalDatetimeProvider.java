package app.dodb.smd.api.metadata;

import java.time.LocalDateTime;

public class LocalDatetimeProvider implements DatetimeProvider {

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
