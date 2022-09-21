import {ServerErrorWithCause} from "../../data/result";
import {details, div, icon, span, tag, TagElement} from "../tags";
import {copyToClipboard} from "../component/notebook/cell";

export class ErrorEl {
    el: TagElement<"div" | "details">;
    private constructor(readonly summary: { label: string; message: string },
                        readonly trace: { cause?: ErrorEl, items: { content: string; type?: "link" | "irrelevant" }[] },
                        readonly errorLine?: number,
                        readonly showCopyEl? : boolean) {

        const traceItems = trace.items.map(item => {
            if (item.type === "link") {
                return tag('li', [], {}, [span(['error-link'], [item.content])])
            } else if (item.type === "irrelevant") {
                return tag('li', ['irrelevant'], {}, [item.content])
            } else {
                return tag('li', [], {}, [item.content])
            }
        });

        const copyEl = icon(['copy-button'], 'copy', 'copy icon').click(() => copyToClipboard(this.copyFromServerError()));
        const causeEl = trace.cause?.el;
        const summaryContent = [span(['severity'], [summary.label]), span(['message'], [summary.message])];
        if (traceItems.length > 0) {
            const traceContent = [tag('ul', ['stack-trace'], {}, traceItems), causeEl];
            this.el = div([], [showCopyEl ? copyEl : '', details([], summaryContent, traceContent)]);
        } else {
            this.el = div([], summaryContent);
        }
    }

    static fromServerError(err: ServerErrorWithCause, filename?: string, showCopyEl: boolean = false, maxDepth: number = 10, nested: boolean = false): ErrorEl {
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
            ? ErrorEl.fromServerError(err.cause, filename, showCopyEl, maxDepth - 1, true)
            : undefined;
        const label = nested ? "Caused by: " : "Uncaught exception: ";
        const summary = {label, message};
        const trace = {items, cause};
        return new ErrorEl(summary, trace, errorLine, showCopyEl);
    }

    copyFromServerError(): string {
        let res = this.summary.label + this.summary.message + '\n';

        this.trace.items.forEach((item) => {
            res += '\n| ' + item.content;
        })

        return res;
    }
}

export function getErrorLine(err: ServerErrorWithCause, filename: string): number | undefined {
    let errorLine: number | undefined = undefined;

    if (err.stackTrace?.length > 0) {
        err.stackTrace.forEach((traceEl, i) => {
            if (traceEl.file === filename && traceEl.line >= 0) {
                if (errorLine === undefined)
                    errorLine = traceEl.line ?? undefined;
            }
        });
    }

    return errorLine;
}