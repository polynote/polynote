package polynote.runtime;

import java.io.Serializable;

public class KernelRuntime implements Serializable {
    public interface Display extends Serializable {
        void content(String contentType, String content);

        default void html(String content) { content("text/html", content); }
        default void write(String str) { content("text/plain; rel=stdout", str); }
        default void text(String str) { content("text/plain", str); }
    }

    transient public final Display display;
    transient private final scala.Function2<Double, String, scala.Unit> progressSetter;
    transient private final scala.Function1<scala.Option<scala.Tuple2<Integer, Integer>>, scala.Unit> executionStatusSetter;

    public KernelRuntime(
            Display display,
            scala.Function2<Double, String, scala.Unit> progressSetter,
            scala.Function1<scala.Option<scala.Tuple2<Integer, Integer>>, scala.Unit> executionStatusSetter) {
        this.display = display;
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
