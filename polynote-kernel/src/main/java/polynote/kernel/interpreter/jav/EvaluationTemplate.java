package polynote.kernel.interpreter.jav;

@SuppressWarnings("unused")
public abstract class EvaluationTemplate {
    private final EvaluationLookup lookup;

    protected EvaluationTemplate(EvaluationLookup lookup) {
        this.lookup = lookup;
    }

    @SuppressWarnings("unchecked")
    protected final <T> T valueOf(final String key) {
        return lookup.valueOf(key);
    }
}
