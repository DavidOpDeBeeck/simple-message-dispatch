package app.dodb.smd.test;

import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;

import static java.util.Objects.requireNonNull;

public class PrincipalProviderStub implements PrincipalProvider {

    private Principal principal;
    private final PrincipalProvider delegate;

    public PrincipalProviderStub(PrincipalProvider delegate) {
        this.delegate = requireNonNull(delegate);
    }

    public void stubPrincipal(Principal principal) {
        this.principal = principal;
    }

    public void reset() {
        this.principal = null;
    }

    @Override
    public Principal get() {
        return principal == null ? delegate.get() : principal;
    }
}
