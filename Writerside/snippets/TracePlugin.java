package acme;

import de.splatgames.aether.weaver.api.spi.PluginContext;
import de.splatgames.aether.weaver.api.spi.PluginEvent;
import de.splatgames.aether.weaver.api.spi.PluginId;
import de.splatgames.aether.weaver.api.spi.WeaverApi;
import de.splatgames.aether.weaver.api.spi.WeaverPlugin;

public final class TracePlugin implements WeaverPlugin {

    private static final PluginId ID =
            new PluginId("acme", "Acme Trace", "1.0");

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
        ctx.metadata("trace", "on").observeApply();
    }

    @Override
    public void observe(PluginEvent event) {
        if (event instanceof PluginEvent.ClassWoven woven) {
            System.out.println("acme wove " + woven.internalName()
                    + ": " + woven.entriesApplied() + " applied");
        }
    }
}
