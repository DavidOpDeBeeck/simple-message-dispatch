package app.dodb.smd.spring;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

import static java.util.Arrays.asList;

public class SMDRegistrar implements ImportBeanDefinitionRegistrar {

    private static final String BEAN_NAME = SMDProperties.class.getName();

    @Override
    public void registerBeanDefinitions(AnnotationMetadata annotationMetadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes = annotationMetadata
            .getAnnotationAttributes(EnableSMD.class.getName());

        if (attributes == null) {
            return;
        }

        String[] packages = (String[]) attributes.get("packages");

        BeanDefinitionBuilder builder = BeanDefinitionBuilder
            .genericBeanDefinition(SMDProperties.class)
            .addConstructorArgValue(asList(packages));

        registry.registerBeanDefinition(BEAN_NAME, builder.getBeanDefinition());
    }
}