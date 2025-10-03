package app.dodb.smd.api.metadata.datetime;

import java.time.LocalDateTime;

public interface DatetimeProvider {

    LocalDateTime now();
}
