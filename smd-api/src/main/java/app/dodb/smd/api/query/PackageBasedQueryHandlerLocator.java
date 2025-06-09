package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.ObjectCreator;
import com.google.common.base.Stopwatch;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.reflections.scanners.Scanners.MethodsAnnotated;

public class PackageBasedQueryHandlerLocator implements QueryHandlerLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackageBasedQueryHandlerLocator.class);

    private final List<String> packageNames;
    private final ObjectCreator objectCreator;

    public PackageBasedQueryHandlerLocator(List<String> packageNames, ObjectCreator objectCreator) {
        this.packageNames = requireNonNull(packageNames);
        this.objectCreator = requireNonNull(objectCreator);
    }

    @Override
    public QueryHandlerRegistry locate() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        ConfigurationBuilder configuration = new ConfigurationBuilder()
            .forPackages(packageNames.toArray(new String[0]))
            .setScanners(MethodsAnnotated)
            .setParallel(true);

        QueryHandlerRegistry registry = new Reflections(configuration)
            .getMethodsAnnotatedWith(QueryHandler.class).parallelStream()
            .map(Method::getDeclaringClass)
            .distinct()
            .map(objectCreator::create)
            .map(AnnotatedQueryHandler::from)
            .reduce(QueryHandlerRegistry.empty(), QueryHandlerRegistry::and);

        LOGGER.info("Registered {} query handlers in {}", registry.queryHandlers().size(), stopwatch.stop());
        return registry;
    }
}
