package polynote.runtime;

import java.io.Serializable;

public class KernelRuntime implements Serializable {
    public interface Display extends Serializable {
        void content(String contentType, String content);

        // TODO(jindig): I removed write() and a few other methods from here. Should we keep them for backwards-compatibility?
        default void html(String content) { content("text/html", content); }
        default void text(String str) { content("text/plain", str); }
    }

    // Implement a File-like object (https://docs.python.org/3/glossary.html#term-file-object) for writing plaintext output
    public static class Output implements Serializable {
        transient private final Display display;
        transient private final String type;

        public Output(Display display, String type) {
            this.display = display;
            this.type = type;
        }

        public void write(String str) { display.content(type, str); }
        public void writelines(String[] lines) {
            for (final String line : lines) {
                write(line);
            }
        }

        // a bunch of stub methods and fields from the IOBase interface: https://docs.python.org/3/library/io.html#io.IOBase
        public void close() {}
        public final boolean closed = false;
        public int fileno() { return 0; }
        public void flush() {} // So python doesn't error on sys.stdout.flush().
        public Boolean isatty() { return false; } // so python doesn't error when checking isatty(), as some libs seem to do.
        public boolean readable() { return false; }
        public String readline() { return ""; }
        public String readline(int size) { return ""; }
        public int seek(int offset, int whence) { return 0; }
        public int tell() { return 0; }
        public int truncate(Integer size) { return 0; }
        public boolean writable() { return true; }

        // a bunch of stub methods and fields from the TextIOBase interface: https://docs.python.org/3/library/io.html#io.TextIOBase
        public final String encoding = "utf-8";
        public final String errors = "strict";
        public final String newlines = null;
        public final Object buffer = null;

        public void detach() {}
        public String read() { return ""; }
        public String read(int size) { return ""; }
    }

    transient public final Display display;
    transient public final Output stdout;
    transient public final Output stderr;
    transient private final scala.Function2<Double, String, scala.Unit> progressSetter;
    transient private final scala.Function1<scala.Option<scala.Tuple2<Integer, Integer>>, scala.Unit> executionStatusSetter;

    public KernelRuntime(
            Display display,
            scala.Function2<Double, String, scala.Unit> progressSetter,
            scala.Function1<scala.Option<scala.Tuple2<Integer, Integer>>, scala.Unit> executionStatusSetter) {
        this.display = display;
        this.stdout = new Output(display, "text/plain; rel=stdout");
        this.stderr = new Output(display, "text/plain; rel=stderr");
        this.progressSetter = progressSetter;
        this.executionStatusSetter = executionStatusSetter;
    }

    public void setProgress(double progress, String detail) {
        progressSetter.apply(progress, detail);
    }

    public void setExecutionStatus(int startPos, int endPos) {
        executionStatusSetter.apply(new scala.Some<>(new scala.Tuple2<>(startPos, endPos)));
    }

    public void clearExecutionStatus() {
        executionStatusSetter.apply(scala.Option.empty());
    }

}
