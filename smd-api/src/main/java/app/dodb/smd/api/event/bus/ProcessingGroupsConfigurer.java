package app.dodb.smd.api.event.bus;

import java.util.List;

public interface ProcessingGroupsConfigurer {

    void configure(EventBusSpec.ProcessingGroupsSpec spec);

    static ProcessingGroupsConfigurer multi(List<ProcessingGroupsConfigurer> configurers) {
        return spec -> configurers.forEach(configurer -> configurer.configure(spec));
    }

    static ProcessingGroupsConfigurer defaultBlocking() {
        return spec -> spec.anyProcessingGroup().blocking();
    }
}
