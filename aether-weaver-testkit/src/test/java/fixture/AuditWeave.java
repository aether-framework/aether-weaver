package fixture;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

@Weave(Target.class)
public final class AuditWeave {

    public AuditWeave() {
        // An instance weave is dissolved into its target, so this constructor is never called.
    }

    @Inject(method = "charge(int)", at = @At(Point.HEAD))
    void onCharge() {
        Trace.record("charge");
    }
}
