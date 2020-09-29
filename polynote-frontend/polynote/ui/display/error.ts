import {ServerErrorWithCause} from "../../data/result";
import {details, div, span, tag, TagElement} from "../tags";

export class ErrorEl {
    el: TagElement<"div" | "details">;
    private constructor(readonly summary: { label: string; message: string },
                        readonly trace: { cause?: ErrorEl, items: { content: string; type?: "link" | "irrelevant" }[] },
                        readonly errorLine?: number) {

        const traceItems = trace.items.map(item => {
            if (item.type === "link") {
                return tag('li', [], {}, [span(['error-link'], [item.content])])
            } else if (item.type === "irrelevant") {
                return tag('li', ['irrelevant'], {}, [item.content])
            } else {
                return tag('li', [], {}, [item.content])
            }
        });

        const causeEl = trace.cause?.el;
        const summaryContent = [span(['severity'], [summary.label]), span(['message'], [summary.message])];
        if (traceItems.length > 0) {
            const traceContent = [tag('ul', ['stack-trace'], {}, traceItems), causeEl];
            this.el = details([], summaryContent, traceContent)
        } else {
            this.el = div([], summaryContent)
        }
    }

    static fromServerError(err: ServerErrorWithCause, filename?: string, maxDepth: number = 0, nested: boolean = false): ErrorEl {
        let errorLine: number | undefined = undefined;
        const items: ErrorEl["trace"]["items"] = [];
        const message = `${err.message} (${err.className})`;

        let reachedIrrelevant = false;

        if (err.stackTrace?.length > 0) {
            err.stackTrace.forEach((traceEl, i) => {
                if (traceEl.file === filename && traceEl.line >= 0) {
                    if (errorLine === undefined)
                        errorLine = traceEl.line ?? undefined;
                    items.push({content: `(Line ${traceEl.line})`, type: "link"});
                } else {
                    if (traceEl.className === 'sun.reflect.NativeMethodAccessorImpl') { // TODO: seems hacky, maybe this logic fits better on the backend?
                        reachedIrrelevant = true;
                    }
                    items.push({
                        content: `${traceEl.className}.${traceEl.method}(${traceEl.file}:${traceEl.line})`,
                        type: reachedIrrelevant ? 'irrelevant' : undefined})
                }
            });
        }

        const cause = (maxDepth > 0 && err.cause)
            ? ErrorEl.fromServerError(err.cause, filename, maxDepth - 1, true)
            : undefined;
        const label = nested ? "Caused by: " : "Uncaught exception: ";
        const summary = {label, message};
        const trace = {items, cause};
        return new ErrorEl(summary, trace, errorLine)
    }
}
