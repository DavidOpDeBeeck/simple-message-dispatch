package app.dodb.smd.spring.test;

import app.dodb.smd.api.metadata.datetime.DatetimeProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.test.DatetimeProviderStub;
import app.dodb.smd.test.PrincipalProviderStub;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.stereotype.Component;

import static app.dodb.smd.spring.test.scope.SMDTestScope.SCOPE_SMD_TEST;

@Component
public class SMDStubsPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        // These beans are registered outside of @Bean because they are decorators that delegate to the provided implementation.
        // They are also proxied with 'proxyTargetClass' so they can be correctly used with the 'smd-test' scope.
        registerPrincipalProviderStub(registry, beanFactory);
        registerDatetimeProviderStub(registry, beanFactory);
    }

    private static void registerPrincipalProviderStub(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory beanFactory) {
        RootBeanDefinition beanDefinition = new RootBeanDefinition(PrincipalProviderStub.class);
        beanDefinition.setPrimary(true);
        beanDefinition.setScope(SCOPE_SMD_TEST);
        beanDefinition.getConstructorArgumentValues().addIndexedArgumentValue(0, beanFactory.getBean(PrincipalProvider.class));

        BeanDefinitionHolder beanDefinitionHolder = new BeanDefinitionHolder(beanDefinition, "scopedTarget.principalProviderStub");
        BeanDefinition scopedBeanDefinition = ScopedProxyUtils.createScopedProxy(beanDefinitionHolder, registry, true).getBeanDefinition();
        registry.registerBeanDefinition("principalProviderStub", scopedBeanDefinition);
    }

    private static void registerDatetimeProviderStub(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory beanFactory) {
        RootBeanDefinition beanDefinition = new RootBeanDefinition(DatetimeProviderStub.class);
        beanDefinition.setPrimary(true);
        beanDefinition.setScope(SCOPE_SMD_TEST);
        beanDefinition.getConstructorArgumentValues().addIndexedArgumentValue(0, beanFactory.getBean(DatetimeProvider.class));

        BeanDefinitionHolder beanDefinitionHolder = new BeanDefinitionHolder(beanDefinition, "scopedTarget.datetimeProviderStub");
        BeanDefinition scopedBeanDefinition = ScopedProxyUtils.createScopedProxy(beanDefinitionHolder, registry, true).getBeanDefinition();
        registry.registerBeanDefinition("datetimeProviderStub", scopedBeanDefinition);
    }
}