package app.dodb.smd.api.metadata.datetime;

import java.time.LocalDateTime;

public class LocalDatetimeProvider implements DatetimeProvider {

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
