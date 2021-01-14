package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.functions.Function1;

import static com.googlecode.totallylazy.Sequences.sequence;

public class CompletionCandidate {
    private final String value;
    private final Sequence<String> forms;

    public CompletionCandidate(String value, Sequence<String> forms) {
        this.value = value;
        this.forms = forms;
    }

    public String value() {
        return value;
    }

    public Sequence<String> forms() {
        return forms;
    }

    @Override
    public String toString() {
        return value + forms.toString(" (", ", ", ")");
    }


    @Override
    public int hashCode() {
        return (value != null ? value.hashCode() : 0) +
                (forms != null ? forms.hashCode() : 0);
    }


    @Override
    public boolean equals(Object other) {
        return other instanceof CompletionCandidate &&
                other.getClass().equals(getClass()) &&
                (value != null && value.equals(((CompletionCandidate) other).value)) &&
                (forms != null && forms.equals(((CompletionCandidate) other).forms));
    }

    public static Function1<String, CompletionCandidate> asCompletionCandidate() {
        return candidate -> new CompletionCandidate(candidate, sequence(candidate));
    }
}
