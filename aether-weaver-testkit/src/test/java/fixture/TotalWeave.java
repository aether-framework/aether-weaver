package fixture;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

@Weave(Target.class)
public final class TotalWeave {

    public TotalWeave() {
        // Dissolved into the target, so never called.
    }

    @Inject(method = "total(long,long)", at = @At(Point.HEAD))
    void onTotal() {
        Trace.record("total");
    }
}
