package acme;

import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.InjectionPointFactory;

import java.util.Set;

public final class AcmePoints
        implements InjectionPointFactory {

    static final String ID = "acme:AFTER_LOGGING";

    private static final InjectionPoint POINT =
            new AfterLogging();

    @Override
    public String namespace() {
        return "acme";
    }

    @Override
    public Set<String> ids() {
        return Set.of(ID);
    }

    @Override
    public InjectionPoint create(String id) {
        return POINT;
    }
}
