package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Sequences;
import com.googlecode.totallylazy.json.Json;
import polynote.kernel.interpreter.jav.javarepl.client.JavaREPLClient;

import java.util.List;
import java.util.Map;

import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.collections.PersistentMap.constructors.emptyMap;
import static com.googlecode.totallylazy.collections.PersistentMap.constructors.map;
import static com.googlecode.totallylazy.json.Json.json;
import static polynote.kernel.interpreter.jav.javarepl.client.JavaREPLClient.*;


public class CompletionResult {
    private final String expression;
    private final Integer position;
    private final Sequence<CompletionCandidate> candidates;

    public CompletionResult(String expression, Integer position, Sequence<CompletionCandidate> candidates) {
        this.expression = expression;
        this.position = position;
        this.candidates = candidates;
    }

    public Integer position() {
        return position;
    }

    public String expression() {
        return expression;
    }

    public Sequence<CompletionCandidate> candidates() {
        return candidates;
    }

    @Override
    public String toString() {
        return expression + " -> " + candidates.toString("[", ",", "]") + " @ " + position;
    }

    @Override
    public int hashCode() {
        return (expression != null ? expression.hashCode() : 0) +
                (candidates != null ? candidates.hashCode() : 0) +
                (position != null ? position.hashCode() : 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompletionResult &&
                other.getClass().equals(getClass()) &&
                (expression != null && expression.equals(((CompletionResult) other).expression)) &&
                (candidates != null && candidates.equals(((CompletionResult) other).candidates)) &&
                (position != null && position.equals(((CompletionResult) other).position));
    }


    public static final class methods {
        public static String toJson(CompletionResult result) {
            return json(emptyMap(String.class, Object.class)
                    .insert("expression", result.expression())
                    .insert("position", result.position().toString())
                    .insert("candidates", result.candidates().map(completionCandidate -> emptyMap(String.class, Object.class)
                            .insert("value", completionCandidate.value())
                            .insert("forms", completionCandidate.forms().toList())).toList()));
        }

        public static CompletionResult fromJson(String json) {
            Map<String, Object> jsonMap = map(Json.map(json));

            Sequence<Map<String, Object>> candidates = CANDIDATES.map(Sequences::sequence).apply(jsonMap);

            return new CompletionResult(
                    EXPRESSION.apply(jsonMap),
                    POSITION.map(Integer::valueOf).apply(jsonMap),
                    candidates.map(candidate -> new CompletionCandidate(VALUE.apply(candidate), FORMS.map(Sequences::sequence).apply(candidate)))
            );
        }
    }
}
