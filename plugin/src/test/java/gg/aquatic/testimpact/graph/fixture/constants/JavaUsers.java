package gg.aquatic.testimpact.graph.fixture.constants;

public class JavaUsers {
    public int doubled() {
        return JavaConstView.SIZE * 2;
    }

    public int debug() {
        if (JavaConstView.DEBUG) {
            return 1;
        }
        return 0;
    }

    public int samePackage() {
        return PackageConstView.HIDDEN;
    }
}
