package app.dodb.smd.api.command;

import java.util.List;

public interface CommandHandlerLocator {

    CommandHandlerRegistry locate();

    static CommandHandlerLocator combine(List<CommandHandlerLocator> locators) {
        return () -> locators.stream()
            .map(CommandHandlerLocator::locate)
            .reduce(CommandHandlerRegistry::and)
            .orElse(CommandHandlerRegistry.empty());
    }
}
