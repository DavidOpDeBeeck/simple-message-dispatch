package app.dodb.smd.api.command;

import app.dodb.smd.api.framework.ObjectCreator;
import com.google.common.base.Stopwatch;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.reflections.scanners.Scanners.MethodsAnnotated;

public class PackageBasedCommandHandlerLocator implements CommandHandlerLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackageBasedCommandHandlerLocator.class);

    private final List<String> packageNames;
    private final ObjectCreator objectCreator;

    public PackageBasedCommandHandlerLocator(List<String> packageNames, ObjectCreator objectCreator) {
        this.packageNames = requireNonNull(packageNames);
        this.objectCreator = requireNonNull(objectCreator);
    }

    @Override
    public CommandHandlerRegistry locate() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        FilterBuilder inputsFilter = new FilterBuilder();
        packageNames.forEach(inputsFilter::includePackage);

        ConfigurationBuilder configuration = new ConfigurationBuilder()
            .forPackages(packageNames.toArray(new String[0]))
            .setScanners(MethodsAnnotated)
            .setParallel(true)
            .filterInputsBy(inputsFilter);

        CommandHandlerRegistry registry = new Reflections(configuration)
            .getMethodsAnnotatedWith(CommandHandler.class).parallelStream()
            .map(Method::getDeclaringClass)
            .distinct()
            .map(objectCreator::create)
            .map(AnnotatedCommandHandler::from)
            .reduce(CommandHandlerRegistry.empty(), CommandHandlerRegistry::and);

        LOGGER.info("Located {} command handlers in {}. Packages scanned: {}", registry.commandHandlers().size(), stopwatch.stop(), packageNames);
        return registry;
    }
}
