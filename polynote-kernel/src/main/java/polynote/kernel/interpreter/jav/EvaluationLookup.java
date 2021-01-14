package polynote.kernel.interpreter.jav;

/**
 * @author dray
 */
public interface EvaluationLookup {
    <T> T valueOf(final String key);
}
