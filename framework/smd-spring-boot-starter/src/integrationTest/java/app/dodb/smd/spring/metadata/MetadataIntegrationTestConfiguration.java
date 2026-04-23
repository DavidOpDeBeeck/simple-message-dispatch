package app.dodb.smd.spring.metadata;

import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.metadata.example.MetadataExampleIntegrationTestConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@EnableSMD
@Configuration
@Import(MetadataExampleIntegrationTestConfiguration.class)
public class MetadataIntegrationTestConfiguration {
}
