package gg.aquatic.testimpact.seed.fixture.res;

import java.net.URL;

public class RelativeLoader {
    public URL find() {
        return RelativeLoader.class.getResource("static.txt");
    }
}
