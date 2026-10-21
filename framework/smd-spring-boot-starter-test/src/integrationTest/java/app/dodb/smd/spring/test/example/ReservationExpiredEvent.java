package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.event.Event;

public record ReservationExpiredEvent(String account) implements Event {
}
