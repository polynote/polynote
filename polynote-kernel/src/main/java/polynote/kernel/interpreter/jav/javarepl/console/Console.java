package polynote.kernel.interpreter.jav.javarepl.console;

import polynote.kernel.interpreter.jav.javarepl.completion.CompletionResult;
import polynote.kernel.interpreter.jav.javarepl.rendering.ExpressionTemplate;

public interface Console {

    ConsoleResult execute(String expression);

    CompletionResult completion(String expression);

    ExpressionTemplate template(String expression);

    ConsoleStatus status();

    ConsoleHistory history();

    void start();

    void shutdown();

}
