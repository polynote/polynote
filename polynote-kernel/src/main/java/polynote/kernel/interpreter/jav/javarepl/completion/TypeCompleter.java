package polynote.kernel.interpreter.jav.javarepl.completion;


import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Strings;
import com.googlecode.totallylazy.functions.Function1;

import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Strings.startsWith;
import static com.googlecode.totallylazy.comparators.Comparators.ascending;
import static com.googlecode.totallylazy.predicates.Predicates.*;
import static polynote.kernel.interpreter.jav.javarepl.completion.CompletionCandidate.asCompletionCandidate;

public final class TypeCompleter extends Completer {
    private final TypeResolver typeResolver;

    public TypeCompleter(TypeResolver typeResolver) {
        this.typeResolver = typeResolver;
    }

    public CompletionResult call(String expression) throws Exception {
        final int lastSpace = expression.lastIndexOf(" ") + 1;
        final String packagePart = expression.substring(lastSpace);
        final int beginIndex = packagePart.lastIndexOf('.') + 1;

        Sequence<ResolvedPackage> resolvedPackages = typeResolver.packages().filter(where(ResolvedPackage::packageName, startsWith(packagePart)));

        Sequence<String> classesInPackage = beginIndex > 0
                ? typeResolver.packages().filter(where(ResolvedPackage::packageName, equalTo(packagePart.substring(0, beginIndex - 1))))
                .flatMap(ResolvedPackage::classes)
                .map(ResolvedClass::canonicalClassName)
                .filter(startsWith(packagePart))
                : empty(String.class);

        Sequence<CompletionCandidate> candidates = resolvedPackages.map(ResolvedPackage::packageName).join(classesInPackage)
                .map(candidatePackagePrefix(beginIndex))
                .filter(not(Strings.empty()))
                .unique()
                .sort(ascending(String.class))
                .map(asCompletionCandidate());

        return new CompletionResult(expression, lastSpace + beginIndex, candidates);
    }

    private Function1<String, String> candidatePackagePrefix(final int fromIndex) {
        return item -> {
            int toIndex = item.indexOf('.', fromIndex);

            if (toIndex < fromIndex)
                toIndex = item.length();

            return item.substring(fromIndex, toIndex);
        };
    }
}
