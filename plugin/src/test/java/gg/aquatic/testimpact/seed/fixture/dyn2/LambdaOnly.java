package gg.aquatic.testimpact.seed.fixture.dyn2;

import java.util.function.Supplier;

public class LambdaOnly {
    public Supplier<String> greeting(String name) {
        return () -> "hello " + name + " from " + name.length();
    }
}
