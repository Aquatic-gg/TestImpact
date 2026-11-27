package gg.aquatic.testimpact.seed.fixture.res;

public class DynamicLoaderCheck {
    public Object run() {
        return new DynamicLoader().find("name");
    }
}
