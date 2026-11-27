package gg.aquatic.testimpact.seed.fixture.res;

import java.net.URL;

public class StaticLoader {
    public URL find() {
        return StaticLoader.class.getResource("/gg/aquatic/testimpact/seed/fixture/res/static.txt");
    }
}
