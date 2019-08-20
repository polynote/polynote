package polynote.runtime;

import java.io.Serializable;

public class KernelRuntime implements Serializable {
    public static interface Display {
        default void html(String content) {
            content("text/html", content);
        }

        void content(String contentType, String content);
    }

    public final Display display;
    private final scala.Function2<Double, String, scala.Unit> progressSetter;
    private final scala.Function1<scala.Option<scala.Tuple2<Integer, Integer>>, scala.Unit> executionStatusSetter;

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
