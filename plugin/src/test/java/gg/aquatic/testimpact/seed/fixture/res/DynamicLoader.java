package gg.aquatic.testimpact.seed.fixture.res;

import java.net.URL;

public class DynamicLoader {
    public URL find(String file) {
        StringBuilder name = new StringBuilder("/gg/aquatic/testimpact/seed/fixture/res/");
        name.append(file);
        return DynamicLoader.class.getResource(name.toString());
    }
}
