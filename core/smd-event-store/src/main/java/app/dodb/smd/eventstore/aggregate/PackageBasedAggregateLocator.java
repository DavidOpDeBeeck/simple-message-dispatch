package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.CommandHandlerRegistry;
import app.dodb.smd.api.framework.ObjectCreator;
import com.google.common.base.Stopwatch;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;
import static org.reflections.scanners.Scanners.TypesAnnotated;

public class PackageBasedAggregateLocator implements CommandHandlerLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackageBasedAggregateLocator.class);

    private final List<String> packageNames;
    private final EventSourcedAggregates aggregates;
    private final ObjectCreator objectCreator;

    public PackageBasedAggregateLocator(List<String> packageNames, EventSourcedAggregates aggregates, ObjectCreator objectCreator) {
        this.packageNames = requireNonNull(packageNames);
        this.aggregates = requireNonNull(aggregates);
        this.objectCreator = requireNonNull(objectCreator);
    }

    @Override
    public CommandHandlerRegistry locate() {
        var stopwatch = Stopwatch.createStarted();

        var inputsFilter = new FilterBuilder();
        packageNames.forEach(inputsFilter::includePackage);

        var configuration = new ConfigurationBuilder()
            .forPackages(packageNames.toArray(new String[0]))
            .setScanners(TypesAnnotated)
            .setParallel(true)
            .filterInputsBy(inputsFilter);

        var repositories = new Reflections(configuration)
            .getTypesAnnotatedWith(Aggregate.class, true).stream()
            .<AggregateRepository<?>>map(aggregates::repository)
            .toList();
        validateUniqueAggregateTypes(repositories);

        var registry = repositories.parallelStream()
            .map(repository -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .reduce(CommandHandlerRegistry.empty(), CommandHandlerRegistry::and);

        LOGGER.info("Located {} aggregate command handlers on {} aggregates in {}. Packages scanned: {}",
            registry.commandHandlers().size(), repositories.size(), stopwatch.stop(), packageNames);
        return registry;
    }

    private static void validateUniqueAggregateTypes(List<AggregateRepository<?>> repositories) {
        var aggregateClassesByType = new HashMap<String, Class<?>>();
        for (var repository : repositories) {
            var model = repository.model();
            var duplicate = aggregateClassesByType.putIfAbsent(model.aggregateType(), model.aggregateClass());
            if (duplicate != null) {
                throw new IllegalArgumentException("""
                    Duplicate aggregate type name: %s
                    
                        Aggregates:
                        %s, %s
                    """.formatted(model.aggregateType(), logClass(duplicate), logClass(model.aggregateClass())));
            }
        }
    }
}
