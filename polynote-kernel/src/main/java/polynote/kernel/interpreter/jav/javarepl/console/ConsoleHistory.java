package polynote.kernel.interpreter.jav.javarepl.console;

import com.googlecode.totallylazy.Files;
import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Strings;
import com.googlecode.totallylazy.predicates.Predicate;

import java.io.File;

import static com.googlecode.totallylazy.Option.option;
import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Strings.replaceAll;
import static com.googlecode.totallylazy.predicates.Not.not;


public final class ConsoleHistory {
    private final Option<File> file;
    private final Predicate<String> ignored;

    private Sequence<String> history = empty();

    private ConsoleHistory(Sequence<String> history, Predicate<String> ignored, Option<File> file) {
        this.ignored = ignored;
        this.history = addToHistory(history);
        this.file = file;
    }

    public static final ConsoleHistory emptyHistory(Predicate<String> ignored, Option<File> file) {
        return new ConsoleHistory(empty(String.class), ignored, file);
    }

    public static final ConsoleHistory historyFromFile(Predicate<String> ignored, Option<File> file) {
        if (file.isEmpty())
            return emptyHistory(ignored, file);

        try {
            return new ConsoleHistory(Strings.lines(file.get()), ignored, file);
        } catch (Exception e) {
            return emptyHistory(ignored, file);
        }
    }

    public boolean save() {
        if (file.isEmpty())
            return false;

        try {
            Files.write(history.toString("\n").getBytes(), file.get());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public ConsoleHistory add(String expression) {
        history = addToHistory(option(expression).map(replaceAll("\n", " ")).toSequence());
        return this;
    }

    public Sequence<String> items() {
        return history;
    }

    private Sequence<String> addToHistory(Sequence<String> historyToAdd) {
        return history.join(historyToAdd.map(replaceAll("\n", " "))).filter(not(ignored)).reverse().take(300).reverse();
    }

}
