package fixture;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Weave;

@Weave(Service.class)
public final class Audited {

    @Inject(method = "run()",
            at = @At(custom = "acme:AFTER_LOGGING"))
    void onRun() {
        System.out.println("audited");
    }
}
