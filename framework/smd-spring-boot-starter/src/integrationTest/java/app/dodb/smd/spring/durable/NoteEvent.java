package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.Event;

record NoteEvent(String text) implements Event {
}
