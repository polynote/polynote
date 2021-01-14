package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.functions.Function0;

import java.io.File;

import static polynote.kernel.interpreter.jav.javarepl.completion.TypeResolver.functions.classResolver;

public class ResolvedPackage {
    private final String packageName;
    private final File file;
    private final Function0<Sequence<ResolvedClass>> classResolver;

    public ResolvedPackage(File file, String packageName) {
        this.packageName = packageName;
        this.file = file;
        this.classResolver = classResolver(this);
    }

    public String packageName() {
        return packageName;
    }

    public File file() {
        return file;
    }

    public Sequence<ResolvedClass> classes() {
        return classResolver.apply();
    }

    @Override
    public String toString() {
        return packageName + " in " + file;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompletionResult &&
                other.getClass().equals(getClass()) &&
                other.toString().equals(toString());
    }
}
