package app.dodb.smd.test;

import app.dodb.smd.api.metadata.time.TimeProvider;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public class TimeProviderStub implements TimeProvider {

    private Instant timestamp;
    private final TimeProvider delegate;

    public TimeProviderStub(TimeProvider delegate) {
        this.delegate = requireNonNull(delegate);
    }

    public void stubTime(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public void reset() {
        this.timestamp = null;
    }

    @Override
    public Instant now() {
        return timestamp == null ? delegate.now() : timestamp;
    }
}
