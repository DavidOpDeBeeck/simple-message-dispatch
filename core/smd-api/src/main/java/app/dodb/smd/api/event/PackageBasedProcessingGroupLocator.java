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
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.reflections.scanners.Scanners.MethodsAnnotated;

public class PackageBasedProcessingGroupLocator implements ProcessingGroupLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackageBasedProcessingGroupLocator.class);

    private final List<String> packageNames;
    private final ObjectCreator objectCreator;

    public PackageBasedProcessingGroupLocator(List<String> packageNames, ObjectCreator objectCreator) {
        this.packageNames = requireNonNull(packageNames);
        this.objectCreator = requireNonNull(objectCreator);
    }

    @Override
    public ProcessingGroupRegistry locate() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        FilterBuilder inputsFilter = new FilterBuilder();
        packageNames.forEach(inputsFilter::includePackage);

        ConfigurationBuilder configurationBuilder = new ConfigurationBuilder()
            .forPackages(packageNames.toArray(new String[0]))
            .setScanners(MethodsAnnotated)
            .setParallel(true)
            .filterInputsBy(inputsFilter);

        ProcessingGroupRegistry registry = new Reflections(configurationBuilder)
            .getMethodsAnnotatedWith(EventHandler.class).parallelStream()
            .map(Method::getDeclaringClass)
            .distinct()
            .map(objectCreator::create)
            .map(AnnotatedEventHandler::from)
            .reduce(ProcessingGroupRegistry.empty(), ProcessingGroupRegistry::combine);

        stopwatch.stop();

        if (registry.eventHandlerRegistryByProcessingGroup().isEmpty()) {
            LOGGER.info("Located 0 event handlers in {}. Packages scanned: {}", stopwatch, packageNames);
        } else {
            var handlerCountByProcessingGroup = registry.eventHandlerRegistryByProcessingGroup().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().eventHandlers().size()));
            var totalHandlerCount = handlerCountByProcessingGroup.values().stream().mapToInt(Integer::intValue).sum();
            LOGGER.info("Located {} event handlers in {}. Processing groups: {}. Packages scanned: {}", totalHandlerCount, stopwatch, handlerCountByProcessingGroup, packageNames);
        }

        return registry;
    }
}
