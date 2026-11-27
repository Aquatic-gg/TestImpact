package gg.aquatic.testimpact.seed.fixture.dyn;

import java.lang.reflect.Method;

public class Reflector {
    public Object call(Object target) throws ReflectiveOperationException {
        Method method = Object.class.getMethod("toString");
        return method.invoke(target);
    }
}
