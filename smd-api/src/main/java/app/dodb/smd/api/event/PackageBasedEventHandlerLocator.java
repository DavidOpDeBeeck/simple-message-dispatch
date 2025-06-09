package app.dodb.smd.api.event;

import app.dodb.smd.api.framework.ObjectCreator;
import com.google.common.base.Stopwatch;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.reflections.scanners.Scanners.MethodsAnnotated;

public class PackageBasedEventHandlerLocator implements EventHandlerLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackageBasedEventHandlerLocator.class);

    private final List<String> packageNames;
    private final ObjectCreator objectCreator;

    public PackageBasedEventHandlerLocator(List<String> packageNames, ObjectCreator objectCreator) {
        this.packageNames = requireNonNull(packageNames);
        this.objectCreator = requireNonNull(objectCreator);
    }

    @Override
    public EventHandlerRegistry locate() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        ConfigurationBuilder configuration = new ConfigurationBuilder()
            .forPackages(packageNames.toArray(new String[0]))
            .setScanners(MethodsAnnotated)
            .setParallel(true);

        EventHandlerRegistry registry = new Reflections(configuration)
            .getMethodsAnnotatedWith(EventHandler.class).parallelStream()
            .map(Method::getDeclaringClass)
            .distinct()
            .map(objectCreator::create)
            .map(AnnotatedEventHandler::from)
            .reduce(EventHandlerRegistry.empty(), EventHandlerRegistry::and);

        LOGGER.info("Registered {} event handlers in {}", registry.eventHandlersByProcessingGroup().values().stream()
            .mapToLong(Collection::size).sum(), stopwatch.stop());
        return registry;
    }
}
