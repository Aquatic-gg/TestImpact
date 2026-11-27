package gg.aquatic.testimpact.seed.fixture.res;

import java.net.URL;

public class DescriptorLoader {
    public URL find() {
        return DescriptorLoader.class.getResource("plugin.yml");
    }
}
