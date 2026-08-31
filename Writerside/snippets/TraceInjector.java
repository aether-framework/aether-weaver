package acme;

import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;

import java.lang.constant.ClassDesc;
import java.util.List;
import java.util.Set;

public final class TraceInjector implements Injector {

    @Override
    public InjectorKind kind() {
        return AcmeInjectors.TRACE;
    }

    @Override
    public void validate(PlanEntryView entry,
                         TargetView target,
                         Reporter reporter) {
        if (!entry.handler().isStatic()) {
            reporter.report(
                DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER,
                entry.handler().describe() + " must be static");
        }
    }

    @Override
    public Emitter emitter(InjectionContext context) {
        Set<Integer> sites = Set.copyOf(context.sites());
        HandlerRef handler = context.entry().handler();
        ClassDesc owner = context.entry().handlerOwner();

        return (builder, element, index) -> {
            if (sites.contains(index)) {
                HandlerBinding binding =
                        context.argumentsAt(index);
                binding.emitArguments(builder);
                List<HandlerBinding.WriteBack> pending =
                        binding.emitCaptures(builder);
                builder.invokestatic(owner, handler.name(),
                        handler.type());
                HandlerBinding.emitWriteBacks(builder,
                        pending);
            }
            return Disposition.KEEP;
        };
    }
}
