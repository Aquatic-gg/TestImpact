package gg.aquatic.testimpact.seed.fixture.service.impl;

import gg.aquatic.testimpact.seed.fixture.service.api.Greeter;

public class LoudGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "HELLO " + name;
    }
}
