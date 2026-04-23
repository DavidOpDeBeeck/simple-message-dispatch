package app.dodb.smd.spring.metadata;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.metadata.example.MetadataExampleIntegrationTestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@EnableSMD
@Configuration
@Import(MetadataExampleIntegrationTestConfiguration.class)
public class MetadataIntegrationTestConfigurationWithAsyncAwait {

    @Bean
    public ProcessingGroupsConfigurer processingGroupsConfigurer() {
        return spec -> spec
            .processingGroup(ProcessingGroup.DEFAULT).async().await();
    }
}
