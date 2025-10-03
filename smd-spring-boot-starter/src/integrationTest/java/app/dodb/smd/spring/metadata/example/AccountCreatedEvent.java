package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.event.Event;

import static java.util.Objects.requireNonNull;

public record AccountCreatedEvent(String name) implements Event {

    public AccountCreatedEvent {
        requireNonNull(name);
    }
}
