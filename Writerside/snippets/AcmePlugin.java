package acme;

import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;

public final class AcmePlugin implements WeaverPlugin {

    private static final PluginId ID =
            new PluginId("acme", "Acme Points", "1.0");

    @Override
    public PluginId id() {
        return ID;
    }

    @Override
    public int apiLevel() {
        return WeaverApi.LEVEL;
    }

    @Override
    public void contribute(PluginContext ctx) {
        ctx.points(new AcmePoints());
    }
}
