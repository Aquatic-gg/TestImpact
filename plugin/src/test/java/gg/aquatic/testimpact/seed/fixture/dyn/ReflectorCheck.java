package gg.aquatic.testimpact.seed.fixture.dyn;

public class ReflectorCheck {
    public Object run() throws ReflectiveOperationException {
        return new Reflector().call("value");
    }
}
