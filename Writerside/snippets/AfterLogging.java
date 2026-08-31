package acme;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode.Category;
import de.splatgames.aether.weaver.api.diagnostic.PluginDiagnosticId;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.Site.Kind;

import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;

public final class AfterLogging implements InjectionPoint {

    private static final String LOGGER =
            "java/util/logging/Logger";

    private static final PluginDiagnosticId NO_LOGGING =
            new PluginDiagnosticId("acme", "NO_LOGGING",
                    Severity.WARNING,
                    Category.INJECTION_POINT,
                    "the method logs nothing");

    @Override
    public String id() {
        return AcmePoints.ID;
    }

    @Override
    public TargetRequirement targetRequirement() {
        return TargetRequirement.FORBIDDEN;
    }

    @Override
    public boolean supportsShift(At.Shift shift) {
        return shift == At.Shift.NONE;
    }

    @Override
    public List<Site> find(MethodView method,
                           CodeView code,
                           PointSpec spec,
                           Reporter reporter) {
        List<Site> found = new ArrayList<>();
        List<CodeElement> elements = code.elements();

        for (int at = 0; at < elements.size(); at++) {
            CodeElement element = elements.get(at);
            if (element instanceof InvokeInstruction call
                    && LOGGER.equals(owner(call))) {
                found.add(new Site(at, Kind.AFTER_ELEMENT,
                        call));
            }
        }
        if (found.isEmpty()) {
            reporter.report(NO_LOGGING,
                    method.describe() + " logs nothing");
        }
        return List.copyOf(found);
    }

    private static String owner(InvokeInstruction call) {
        return call.owner().asInternalName();
    }
}
