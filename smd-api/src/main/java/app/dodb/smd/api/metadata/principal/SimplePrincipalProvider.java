package app.dodb.smd.api.metadata.principal;

public class SimplePrincipalProvider implements PrincipalProvider {

    @Override
    public SimplePrincipal get() {
        return SimplePrincipal.create();
    }
}
