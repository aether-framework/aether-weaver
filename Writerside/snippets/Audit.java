package fixture;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

@Weave(Target.class)
public final class Audit {

    @Inject(method = "greet()", at = @At(Point.HEAD))
    void onGreet() {
        System.out.println("woven");
    }
}
