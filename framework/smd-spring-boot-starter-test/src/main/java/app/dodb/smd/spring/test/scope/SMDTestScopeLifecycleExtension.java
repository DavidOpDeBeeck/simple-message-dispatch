package app.dodb.smd.spring.test.scope;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SMDTestScopeLifecycleExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        SMDTestScopeContext.getInstance().start(context.getUniqueId());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SMDTestScopeContext.getInstance().stop(context.getUniqueId());
    }
}
