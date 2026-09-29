package berryboy.build;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** Checks resolved external method owners after javac has completed type analysis. */
final class LegacyApiScanner extends TreePathScanner<Void, Void> {
    private static final Map<String, Set<String>> EXTERNAL_METHODS = Map.of(
            "java.lang.Object", Set.of("<init>"),
            "java.lang.String", Set.of("length", "charAt"),
            "java.lang.StringBuffer", Set.of("<init>", "append", "toString"),
            "java.lang.Integer", Set.of("toHexString"),
            "java.lang.System", Set.of("arraycopy"),
            "java.lang.RuntimeException", Set.of("<init>", "getMessage"),
            "java.lang.Throwable", Set.of("getMessage"),
            "java.lang.IllegalArgumentException", Set.of("<init>"));

    private final Trees trees;

    private LegacyApiScanner(Trees trees) {
        this.trees = trees;
    }

    static void validate(CompilationUnitTree unit, Trees trees) {
        new LegacyApiScanner(trees).scan(unit, null);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
        var methodPath = new TreePath(getCurrentPath(), tree.getMethodSelect());
        check(trees.getElement(methodPath));
        return super.visitMethodInvocation(tree, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, Void unused) {
        check(trees.getElement(getCurrentPath()));
        return super.visitNewClass(tree, unused);
    }

    private void check(Element element) {
        if (element == null || !(element.getEnclosingElement() instanceof TypeElement type)) {
            return;
        }
        var owner = type.getQualifiedName().toString();
        if (trees.getPath(type) != null) {
            return;
        }
        var method = element.getSimpleName().toString();
        if (!EXTERNAL_METHODS.getOrDefault(owner, Set.of()).contains(method)) {
            throw new IllegalArgumentException("Unapproved legacy API: " + owner + "." + method);
        }
    }
}
