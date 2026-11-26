package gg.aquatic.testimpact.graph.fixture.constants;

public class JavaUsers {
    public int doubled() {
        return JavaConst.SIZE * 2;
    }

    public int debug() {
        if (JavaConst.DEBUG) {
            return 1;
        }
        return 0;
    }

    public int samePackage() {
        return PackageConst.HIDDEN;
    }
}
