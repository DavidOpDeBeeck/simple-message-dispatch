package app.dodb.smd.api.event;

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

        FilterBuilder inputsFilter = new FilterBuilder();
        packageNames.forEach(inputsFilter::includePackage);

        ConfigurationBuilder configuration = new ConfigurationBuilder()
            .forPackages(packageNames.toArray(new String[0]))
            .setScanners(MethodsAnnotated)
            .setParallel(true)
            .filterInputsBy(inputsFilter);

        EventHandlerRegistry registry = new Reflections(configuration)
            .getMethodsAnnotatedWith(EventHandler.class).parallelStream()
            .map(Method::getDeclaringClass)
            .distinct()
            .map(objectCreator::create)
            .map(AnnotatedEventHandler::from)
            .reduce(EventHandlerRegistry.empty(), EventHandlerRegistry::and);

        stopwatch.stop();

        registry.eventHandlersByProcessingGroup().forEach((processingGroup, handlers) ->
            LOGGER.info("Located {} event handlers for processing group '{}' in {}. Packages scanned: {}", handlers.size(), processingGroup, stopwatch, packageNames));

        if (registry.eventHandlersByProcessingGroup().isEmpty()) {
            LOGGER.info("Located 0 event handlers in {}. Packages scanned: {}", stopwatch, packageNames);
        }
        return registry;
    }
}
