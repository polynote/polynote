package polynote.kernel.interpreter.jav.javarepl;

import com.googlecode.totallylazy.Option;

import static com.googlecode.totallylazy.predicates.Predicates.equalTo;
import static com.googlecode.totallylazy.predicates.Predicates.where;

@SuppressWarnings("unused")
public abstract class EvaluationTemplate {
    private final EvaluationContext context;

    public EvaluationTemplate(EvaluationContext context) {
        this.context = context;
    }

    @SuppressWarnings("unchecked")
    public final <T> T valueOf(final String key) {
        Option<Result> result = context.results()
                .find(where(Result::key, equalTo(key)));

        if (result.isEmpty())
            throw new IllegalArgumentException("Result '" + key + "' not found");

        return (T) result.get().value();
    }
}
