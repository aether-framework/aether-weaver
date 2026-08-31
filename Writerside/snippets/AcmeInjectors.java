package acme;

import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.InjectorFactory;

import java.util.Set;

public final class AcmeInjectors implements InjectorFactory {

    static final InjectorKind TRACE =
            InjectorKind.of("acme:trace");

    private static final Injector TRACE_INJECTOR =
            new TraceInjector();

    @Override
    public String namespace() {
        return "acme";
    }

    @Override
    public Set<InjectorKind> kinds() {
        return Set.of(TRACE);
    }

    @Override
    public Injector create(InjectorKind kind) {
        return TRACE_INJECTOR;
    }
}
