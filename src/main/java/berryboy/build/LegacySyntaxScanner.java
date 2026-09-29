package berryboy.build;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Collects supported syntax rewrites before javac resolves local variable types. */
final class LegacySyntaxScanner extends TreePathScanner<Void, Void> {
    private static final Set<Tree.Kind> REJECTED = Set.of(
            Tree.Kind.LAMBDA_EXPRESSION, Tree.Kind.MEMBER_REFERENCE,
            Tree.Kind.PARAMETERIZED_TYPE, Tree.Kind.TYPE_PARAMETER,
            Tree.Kind.ANNOTATION, Tree.Kind.ANNOTATION_TYPE, Tree.Kind.ENUM,
            Tree.Kind.ENHANCED_FOR_LOOP, Tree.Kind.UNION_TYPE, Tree.Kind.ASSERT);
    private static final Pattern VAR_TOKEN = Pattern.compile(
            "/\\*.*?\\*/|//[^\\r\\n]*|\\bvar\\b", Pattern.DOTALL);

    private final CompilationUnitTree unit;
    private final String source;
    private final SourcePositions positions;
    private final List<Edit> edits = new ArrayList<>();

    private LegacySyntaxScanner(CompilationUnitTree unit, String source, Trees trees) {
        this.unit = unit;
        this.source = source;
        this.positions = trees.getSourcePositions();
    }

    static List<Edit> collectEdits(CompilationUnitTree unit, String source, Trees trees) {
        var scanner = new LegacySyntaxScanner(unit, source, trees);
        scanner.scan(unit, null);
        return List.copyOf(scanner.edits);
    }

    @Override
    public Void scan(Tree tree, Void unused) {
        if (tree != null) {
            var kind = tree.getKind();
            if (REJECTED.contains(kind) || kind.name().contains("PATTERN") || kind == Tree.Kind.RECORD) {
                reject(tree, "Unsupported legacy syntax: " + kind);
            }
        }
        return super.scan(tree, unused);
    }

    @Override
    public Void visitReturn(ReturnTree tree, Void unused) {
        if (tree.getExpression() instanceof SwitchExpressionTree expression) {
            edits.add(new Edit(start(tree), end(tree), lowerSwitch(expression), null));
        }
        return super.visitReturn(tree, unused);
    }

    @Override
    public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
        if (!(getCurrentPath().getParentPath().getLeaf() instanceof ReturnTree)) {
            reject(tree, "Only directly returned switch expressions can be exported");
        }
        return super.visitSwitchExpression(tree, unused);
    }

    @Override
    public Void visitTry(TryTree tree, Void unused) {
        if (!tree.getResources().isEmpty()) {
            reject(tree, "try-with-resources needs a separate backport");
        }
        return super.visitTry(tree, unused);
    }

    @Override
    public Void visitLiteral(LiteralTree tree, Void unused) {
        if (tree.getValue() instanceof Number) {
            var token = source.substring(start(tree), end(tree));
            if (token.contains("_") || token.startsWith("0b") || token.startsWith("0B")) {
                reject(tree, "Use legacy-compatible numeric literal syntax in the shared core");
            }
        }
        return super.visitLiteral(tree, unused);
    }

    @Override
    public Void visitVariable(VariableTree tree, Void unused) {
        if (tree.getType() == null) {
            collectInferredType(tree);
        }
        return super.visitVariable(tree, unused);
    }

    private String lowerSwitch(SwitchExpressionTree expression) {
        var result = new StringBuilder("switch (").append(expression.getExpression()).append(") {\n");
        for (var arm : expression.getCases()) {
            if (arm.getCaseKind() != CaseTree.CaseKind.RULE || !(arm.getBody() instanceof ExpressionTree)) {
                reject(arm, "Only expression arms in a directly returned switch are supported");
            }
            if (arm.getExpressions().isEmpty()) {
                result.append("default: ");
            } else {
                for (var label : arm.getExpressions()) {
                    result.append("case ").append(label).append(": ");
                }
            }
            result.append("return ").append(arm.getBody()).append(";\n");
        }
        return result.append("}").toString();
    }

    private void collectInferredType(VariableTree tree) {
        var declarationStart = start(tree);
        var initializerStart = start(tree.getInitializer());
        var matcher = VAR_TOKEN.matcher(source.substring(declarationStart, initializerStart));
        while (matcher.find()) {
            if ("var".equals(matcher.group())) {
                var tokenStart = declarationStart + matcher.start();
                edits.add(new Edit(tokenStart, tokenStart + 3, null, getCurrentPath()));
                return;
            }
        }
        reject(tree, "Could not locate local var token");
    }

    private int start(Tree tree) {
        return (int) positions.getStartPosition(unit, tree);
    }

    private int end(Tree tree) {
        return (int) positions.getEndPosition(unit, tree);
    }

    private void reject(Tree tree, String reason) {
        var line = unit.getLineMap().getLineNumber(start(tree));
        throw new IllegalArgumentException(unit.getSourceFile().getName() + ":" + line + ": " + reason);
    }

    record Edit(int start, int end, String replacement, TreePath inferredVariable) {
        String resolve(Trees trees) {
            if (inferredVariable == null) {
                return replacement;
            }
            var type = trees.getElement(inferredVariable).asType().toString();
            if (type.contains("<") || type.contains("&") || type.contains("capture#")) {
                throw new IllegalArgumentException("Cannot export inferred type " + type);
            }
            return type;
        }
    }
}
