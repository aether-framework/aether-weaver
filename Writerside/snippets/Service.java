package fixture;

import java.util.logging.Logger;

public class Service {

    private static final Logger LOG =
            Logger.getLogger("fixture");

    public void run() {
        LOG.fine("starting");
        System.out.println("running");
    }
}
