package app.dodb.smd.spring.durable.processing;

import app.dodb.smd.api.event.Event;

public record NoteEvent(String text) implements Event {
}
