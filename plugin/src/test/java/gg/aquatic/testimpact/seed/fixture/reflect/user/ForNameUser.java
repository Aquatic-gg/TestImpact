package gg.aquatic.testimpact.seed.fixture.reflect.user;

public class ForNameUser {
    public Class<?> load() throws ClassNotFoundException {
        return Class.forName("gg.aquatic.testimpact.seed.fixture.reflect.target.ForNameTarget");
    }
}
