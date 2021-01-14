package polynote.kernel.interpreter.jav.javarepl.client;

import com.googlecode.totallylazy.collections.Keyword;

import java.util.List;
import java.util.Map;

import static com.googlecode.totallylazy.collections.Keyword.keyword;
import static com.googlecode.totallylazy.reflection.Types.classOf;
import static com.googlecode.totallylazy.reflection.Types.parameterizedType;

public final class JavaREPLClient {

    public static final Keyword<String> EXPRESSION = keyword("expression", String.class);
    public static final Keyword<String> TEMPLATE = keyword("template", String.class);
    public static final Keyword<String> TOKEN = keyword("token", String.class);
    public static final Keyword<String> TYPE = keyword("type", String.class);
    public static final Keyword<String> MESSAGE = keyword("message", String.class);
    public static final Keyword<String> STATUS = keyword("status", String.class);
    public static final Keyword<String> VERSION = keyword("version", String.class);
    public static final Keyword<String> VALUE = keyword("value", String.class);
    public static final Keyword<String> POSITION = keyword("position", String.class);
    public static final Keyword<List<String>> HISTORY = keyword("history", classOf(parameterizedType(List.class, String.class)));
    public static final Keyword<List<String>> FORMS = keyword("forms", classOf(parameterizedType(List.class, String.class)));
    public static final Keyword<List<Map<String, Object>>> LOGS = keyword("logs", classOf(parameterizedType(List.class, parameterizedType(Map.class, String.class, Object.class))));
    public static final Keyword<List<Map<String, Object>>> CANDIDATES = keyword("candidates", classOf(parameterizedType(List.class, parameterizedType(Map.class, String.class, Object.class))));
/*
    private final String hostname;
    private final Integer port;
    private final ClientHttpHandler client;

    public JavaREPLClient(String hostname, Integer port) {
        this.hostname = hostname;
        this.port = port;
        this.client = new ClientHttpHandler();
    }

    public synchronized ExpressionTemplate template(String expression) throws Exception {
        Map<String, Object> response = map(client.handle(get(url("template")).query("expression", expression)).entity().toString());
        String template = TEMPLATE.call(response);
        String token = TOKEN.call(response);
        return new ExpressionTemplate(template, token);
    }


    public synchronized Option<EvaluationResult> execute(String expr) throws Exception {
        String json = client.handle(post(url("execute")).form("expression", expr)).entity().toString();

        if (json.isEmpty())
            return none();

        Map<String, Object> response = map(json);
        Sequence<Map<String, Object>> logs = LOGS.map(Sequences::sequence).call(response);
        String expression = EXPRESSION.call(response);

        return some(new EvaluationResult(expression, logs.map(modelToEvaluationLog())));
    }

    public synchronized CompletionResult completions(String expr) throws Exception {
        return fromJson(client.handle(get(url("completions")).query("expression", expr)).entity().toString());
    }

    public synchronized ConsoleStatus status() {
        try {
            Map<String, Object> response = map(client.handle(get(url("status"))).entity().toString());
            return consoleStatus(STATUS.call(response));
        } catch (Exception e) {
            return Idle;
        }
    }

    public synchronized String version() {
        try {
            Map<String, Object> response = map(client.handle(get(url("version"))).entity().toString());
            return VERSION.call(response);
        } catch (Exception e) {
            return "[unknown]";
        }
    }

    public synchronized Sequence<String> history() throws Exception {
        Response history = client.handle(get(url("history")));
        Map<String, Object> response = map(history.entity().toString());
        return HISTORY.map(Sequences::sequence).call(response);
    }

    private Function1<Map<String, Object>, EvaluationLog> modelToEvaluationLog() {
        return response -> new EvaluationLog(type(TYPE.call(response)), MESSAGE.call(response));
    }

    private String url(String path) {
        return "http://" + hostname + ":" + port + "/" + path;
    }
 */
}
