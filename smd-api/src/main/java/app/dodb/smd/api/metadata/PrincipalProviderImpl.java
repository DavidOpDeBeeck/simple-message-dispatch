package app.dodb.smd.api.metadata;

public class PrincipalProviderImpl implements PrincipalProvider {

    @Override
    public Principal get() {
        return new Principal();
    }
}
