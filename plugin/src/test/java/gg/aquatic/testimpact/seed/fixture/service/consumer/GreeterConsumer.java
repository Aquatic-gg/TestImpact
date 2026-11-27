package gg.aquatic.testimpact.seed.fixture.service.consumer;

import gg.aquatic.testimpact.seed.fixture.service.api.Greeter;
import java.util.ServiceLoader;

public class GreeterConsumer {
    public static Greeter first() {
        for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
            return greeter;
        }
        return null;
    }
}
