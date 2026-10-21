package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.Command;

public record ExpireReservationCommand(String account) implements Command<Void> {
}
