package app.dodb.smd.eventstore;

enum EventLogConsumption {

    HANDLING,
    FORWARDING;

    boolean addsAttemptMetadata() {
        return this == HANDLING;
    }

    boolean abandonsAfterMaxRetries() {
        return this == HANDLING;
    }
}
