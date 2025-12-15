package app.dodb.smd.spring.test.scope;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static app.dodb.smd.spring.test.scope.SMDTestScope.SCOPE_SMD_TEST;

@Configuration
public class SMDTestScopeConfiguration {

    @Bean
    public BeanFactoryPostProcessor registerScope() {
        return beanFactory -> beanFactory.registerScope(SCOPE_SMD_TEST, new SMDTestScope());
    }
}
