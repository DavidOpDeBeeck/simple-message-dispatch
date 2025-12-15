package app.dodb.smd.test;

import app.dodb.smd.api.metadata.datetime.DatetimeProvider;

import java.time.LocalDateTime;

import static java.util.Objects.requireNonNull;

public class DatetimeProviderStub implements DatetimeProvider {

    private LocalDateTime localDateTime;
    private final DatetimeProvider delegate;

    public DatetimeProviderStub(DatetimeProvider delegate) {
        this.delegate = requireNonNull(delegate);
    }

    public void stubLocalDateTime(LocalDateTime localDateTime) {
        this.localDateTime = localDateTime;
    }

    public void reset() {
        this.localDateTime = null;
    }

    @Override
    public LocalDateTime now() {
        return localDateTime == null ? delegate.now() : localDateTime;
    }
}
