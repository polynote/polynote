package polynote.kernel.interpreter.jav.javarepl.completion;

import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.comparators.Comparators.ascending;

public final class Completers {
    public static Completer javaKeywordCompleter() {
        return new StringCompleter(
                sequence("abstract", "assert", "boolean", "break", "byte", "case", "catch",
                        "char", "class", "continue", "default", "double", "do", "else", "enum",
                        "extends", "false", "final", "finally", "float", "for", "if", "implements",
                        "import", "instanceof", "int", "interface", "long", "native", "new", "null",
                        "package", "private", "protected", "public", "return", "short", "static",
                        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
                        "transient", "true", "try", "void", "volatile", "while").sort(ascending(String.class)));
    }
}
