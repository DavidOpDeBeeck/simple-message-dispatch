package app.dodb.smd.api.event;

import java.util.List;

public interface ProcessingGroupLocator {

    ProcessingGroupRegistry locate();

    static ProcessingGroupLocator combine(List<ProcessingGroupLocator> locators) {
        return () -> locators.stream()
            .map(ProcessingGroupLocator::locate)
            .reduce(ProcessingGroupRegistry::and)
            .orElse(ProcessingGroupRegistry.empty());
    }
}
