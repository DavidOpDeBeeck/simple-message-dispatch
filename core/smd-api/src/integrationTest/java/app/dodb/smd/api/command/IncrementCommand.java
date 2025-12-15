package app.dodb.smd.api.command;

public record IncrementCommand(int value) implements Command<Integer> {
}
