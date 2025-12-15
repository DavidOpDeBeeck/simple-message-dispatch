package app.dodb.smd.spring.command;

import app.dodb.smd.api.command.Command;

public record IncrementCommand(int value) implements Command<Integer> {
}
