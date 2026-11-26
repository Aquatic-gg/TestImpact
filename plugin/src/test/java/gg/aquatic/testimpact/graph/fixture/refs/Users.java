package gg.aquatic.testimpact.graph.fixture.refs;

import java.util.List;
import java.util.function.Supplier;

class FieldUser {
    FieldTarget field;
}

class MethodUser {
    ReturnType run(ParamType param) {
        return null;
    }
}

class CtorUser {
    Object make() {
        return new CtorTarget();
    }
}

class LiteralUser {
    Class<?> type() {
        return LiteralTarget.class;
    }
}

@ClassMarker
class ClassAnnotated {}

@RuntimeMarker(mode = Mode.ON, type = Payload.class)
class RuntimeAnnotated {}

class TypeUseUser {
    Object cast(Object value) {
        return (@TypeUseMarker CastTarget) value;
    }
}

class SignatureOnlyUser {
    List<SigTarget> items;
}

class ThrowsUser {
    void run() throws DeclaredException {}
}

class MethodRefUser {
    Supplier<String> supplier() {
        return MethodRefTarget::name;
    }
}

record RecordUser(RecordPart part) {}

sealed interface Shape permits Circle, Square {}

final class Circle implements Shape {}

final class Square implements Shape {}

class NestHostUser {
    private int secret;

    class Inner {
        int read() {
            return secret;
        }
    }
}
