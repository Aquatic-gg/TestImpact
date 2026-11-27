package gg.aquatic.testimpact.graph.fixture.refs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class FieldTarget {}

class ParamType {}

class ReturnType {}

class CtorTarget {}

class LiteralTarget {}

class SigTarget {}

class CastTarget {}

class DeclaredException extends Exception {}

class MethodRefTarget {
    static String name() {
        return "name";
    }
}

class RecordPart {}

class Payload {}

enum Mode {
    ON
}

@Retention(RetentionPolicy.CLASS)
@interface ClassMarker {}

@Retention(RetentionPolicy.RUNTIME)
@interface RuntimeMarker {
    Mode mode();

    Class<?> type();
}

@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(ElementType.TYPE_USE)
@interface TypeUseMarker {}
