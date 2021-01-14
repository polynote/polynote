package polynote.kernel.interpreter.jav.javarepl.expressions;

import com.googlecode.totallylazy.Sequences;
import com.googlecode.totallylazy.regex.Regex;

import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.regex.Regex.regex;
import static java.util.regex.Pattern.DOTALL;
import static polynote.kernel.interpreter.jav.javarepl.Utils.powerSetPermutations;

public class Patterns {
    public static final Regex identifierPattern = captureGroup("[a-zA-Z\\$_][a-zA-Z0-9\\$_]*");
    public static final Regex assignmentPattern = join(identifierPattern, " *=(.*)$");
    public static final Regex typeNamePattern = captureGroup("[a-zA-Z\\$_][a-zA-Z0-9\\.\\$_\\[\\]<>,?& ]*");
    public static final Regex assignmentWithTypeNamePattern = join(typeNamePattern, " +", assignmentPattern);
    public static final Regex importPattern = join("import  *", captureGroup("[^;^ ]*"), " *", optional(";"), ".*");

    public static final Regex visibilityModifiersPattern = oneOf("private +", "public +", "protected +");
    public static final Regex staticModifierPattern = oneOf("static +");

    public static final Regex packagePattern = join(".*package +", captureGroup("[a-zA-Z0-9\\$_\\.]*"), " *;");
    public static final Regex typeExtensibilityModifiersPattern = oneOf("final +", "abstract +");
    public static final Regex typePrefixPattern = oneOf(regexPermutations(typeExtensibilityModifiersPattern, staticModifierPattern, visibilityModifiersPattern), " *");
    public static final Regex typeKindPattern = oneOf("class +", "interface +", "enum +");
    public static final Regex typePattern = join(oneOf(packagePattern, ""), ".*?", typePrefixPattern, typeKindPattern, identifierPattern, ".*\\{.*");

    public static final Regex methodExtensibilityModifiersPattern = oneOf("final +");
    public static final Regex methodPrefixPattern = oneOf(regexPermutations(methodExtensibilityModifiersPattern, staticModifierPattern, visibilityModifiersPattern), " *");
    public static final Regex methodPattern = join(notStartsWith(oneOf("new +")), captureGroup(join(optional(methodPrefixPattern), typeNamePattern, " +", identifierPattern, " *\\(.*?\\)")), " *\\{.*?");

    public static boolean isValidImport(String string) {
        return importPattern.matches(string.trim());
    }

    public static boolean isValidType(String string) {
        return typePattern.matches(string.trim());
    }

    public static boolean isValidMethod(String string) {
        return methodPattern.matches(string.trim());
    }

    public static boolean isValidIdentifier(String string) {
        return identifierPattern.matches(string.trim());
    }

    public static boolean isValidAssignment(String string) {
        return !string.contains("==") && assignmentPattern.matches(string.trim());
    }

    public static boolean isValidAssignmentWithType(String string) {
        return assignmentWithTypeNamePattern.matches(string.trim());
    }

    private static Regex regexPermutations(Regex... patterns) {
        return regex(powerSetPermutations(sequence(patterns)).delete(empty(Regex.class))
                .map(Sequences.toString("(?:", "", ")"))
                .toString("(?:", "|", ")"));
    }

    public static Regex join(Object... items) {
        return regex(sequence(items).toString(""), DOTALL);
    }

    public static Regex captureGroup(Object... items) {
        return regex(sequence(items).toString("(", "", ")"), DOTALL);
    }

    private static Regex oneOf(Object... patterns) {
        return regex(sequence(patterns).toString("(?:", "|", ")"));
    }

    private static Regex optional(Object pattern) {
        return oneOf(pattern, "");
    }

    private static Regex notStartsWith(Object pattern) {
        return regex("^(?!" + pattern + ")");
    }
}
