"use strict";

import * as acorn from "acorn"
import {
    Position,
    KernelReport,
    CompileErrors,
    Output,
    RuntimeError,
    ClientResult,
    splitOutput,
    MIMEClientResult, ResultValue
} from "../data/result";
import embed, {Result} from "vega-embed";
import {DataStream} from "../messaging/datastream";
import {parsePlotDefinition, plotToVegaCode, PlotValidationErrors, validatePlot} from "../ui/input/plot_selector";
import {CellContext, IClientInterpreter} from "./client_interpreter";
import {collectFirstMatch, deepCopy, Deferred, findInstance, positionIn} from "../util/helpers";
import {div} from "../ui/tags";
import {parseViz, Viz} from "../ui/input/viz_selector";
import {DataRepr, MIMERepr, StreamingDataRepr, StringRepr} from "../data/value_repr";
import {displayData, displaySchema, prettyDisplayData} from "../ui/display/display_content";
import {TableView} from "../ui/layout/table_view";
import {StructType} from "../data/data_type";

export const VegaInterpreter: IClientInterpreter = {

    languageTitle: "Vega spec",
    highlightLanguage: "vega",

    interpret(code, cellContext) {
        code = '(' + code + ')';
        let ast;
        try {
            ast = acorn.parse(code, {sourceType: 'script', ecmaVersion: 6, ranges: true })
        } catch (err) {
            console.error(err)
            const pos = err.pos - 1;
            return [new CompileErrors([
                new KernelReport(new Position(`Cell ${cellContext.id}`, pos, pos, pos), err.message, 2)
            ])];
        }
        const wrappedCode = `with(window) return ${code};`;
        const availableValues = cellContext.availableValues;
        if (availableValues.hasOwnProperty('window')) {
            delete availableValues['window'];
        }
        const names = ['window', ...Object.keys(availableValues).map(sanitizeJSVariable)];
        const fn = new Function(...names, wrappedCode).bind({});

        try {
            const spec = fn(windowOverride, ...Object.values(availableValues));
            // some validation of the spec
            if (!spec.data)
                throw new Error("Spec is missing data attribute");

            if (!spec["$schema"]) {
                spec["$schema"] = 'https://vega.github.io/schema/vega-lite/v3.json';
            }

            return [new VegaClientResult(spec)];
        } catch (err) {
            console.error(err)
            return [RuntimeError.fromJS(err)];
        }
    }


};

export function vizResult(id: number, viz: Viz, result: ResultValue, cellContext: CellContext): (ClientResult | CompileErrors | RuntimeError)[] {
    function err(msg: string): CompileErrors[] {
        return [new CompileErrors([
            {message: msg, severity: 2}
        ])]
    }

    const streamRepr = findInstance(result.reprs, StreamingDataRepr);

    switch (viz.type) {
        case "plot":
            if (!streamRepr || !(streamRepr.dataType instanceof StructType)) {
                return err(`Value ${viz.value} is not table-like`);
            }

            // for now, will just generate vega code and delegate to vega interpreter.
            const plotDef = viz.plotDefinition;

            if (!streamRepr) {
                return err(`No such value: ${plotDef.value}`);
            }
            if (!streamRepr.dataType) {
                return err(`Value ${plotDef.value} is not table-like`);
            }

            try {
                validatePlot(plotDef);
                return VegaInterpreter.interpret(plotToVegaCode(plotDef, streamRepr.dataType), cellContext);
            } catch (e) {
                if (e instanceof PlotValidationErrors) {
                    return [e];
                } else throw e;
            }

        case "mime":
            const mimeResult = collectFirstMatch(result.reprs, _ => _.whenInstanceP(MIMERepr, _ => _.mimeType === viz.mimeType, mime => mime));
            if (!mimeResult) {
                return err(`Value ${viz.value} has no MIME representation of type ${viz.mimeType}`);
            }
            return [new MIMEClientResult(mimeResult)];

        case "schema":
            if (!streamRepr || !(streamRepr.dataType instanceof StructType)) {
                return err(`Value ${viz.value} is not table-like`);
            }
            const schemaEl = displaySchema(streamRepr.dataType);
            return [new MIMEClientResult(new MIMERepr("text/html", schemaEl.outerHTML))];

        case "data":
            const dataRepr = findInstance(result.reprs, DataRepr);
            if (!dataRepr) {
                return err(`Value ${viz.value} has no data representation`);
            }
            const mimeRepr = prettyDisplayData(result.name, result.typeName, dataRepr).then(([mimeType, resultFragment]) => {
                return new MIMERepr(mimeType, resultFragment.outerHTML)
            })
            return [new MIMEClientResult(mimeRepr)];

        case "table":
            // TODO: should do this better in so many ways
            if (!cellContext) {
                return [];
            }

            const value = cellContext.availableValues[viz.value];

            if (!value) {
                return err(`No such value: ${viz.value}`);
            }

            if (!(value instanceof DataStream)) {
                return err(`Value ${viz.value} is not a stream`);
            }

            const stream = value as DataStream;
            const table = new TableView(stream);
            const tableViz = viz;

            // TODO: some better way to skip to a certain page
            function pageToTheRightSpot(): Promise<void> {
                if (table.range[0] >= tableViz.rowRange[0])
                    return Promise.resolve();
                else
                    return table.pageNext().then(pageToTheRightSpot)
            }

            const tableHTML = table.pageNext().then(pageToTheRightSpot).then(_ => new MIMERepr("text/html", table.asHTML))

            return [new MIMEClientResult(tableHTML)];

        case "string":
            const stringValue = findInstance(result.reprs, StringRepr)?.string || "";
            return [new MIMEClientResult(new MIMERepr("text/plain", stringValue))];
    }
}

export const VizInterpreter: IClientInterpreter = {
    languageTitle: "Inspect",
    highlightLanguage: "json",
    interpret(code, cellContext): (ClientResult | CompileErrors | RuntimeError)[] {
        try {
            const viz = parseViz(code);
            if (!viz) {
                throw new Error("Cell does not contain a valid visualization description");
            }
            const result = cellContext.resultValues[viz.value];
            return vizResult(cellContext.id, viz, result, cellContext);
        } catch (err) {
            console.error(err)
            const pos = err.lineNumber !== undefined && err.columnNumber !== undefined ?
                positionIn(code, err.lineNumber, err.columnNumber) : 0;
            const message = err.message || "Cell does not contain a valid visualization description";
            return [new CompileErrors([
                new KernelReport(new Position(`Cell ${cellContext.id}`, pos, pos, pos), message, 2)
            ])]
        }
    },
    hidden: true
}

export class VegaClientResult extends ClientResult {
    private runWithOutputEl: (outputEl: HTMLElement) => void;

    private outputEl: Promise<HTMLElement> = new Promise(resolve => this.runWithOutputEl = resolve);

    private runResult: Promise<Result>;

    private deferredOutput: Promise<Output>;

    private responsive: boolean;

    constructor(specObj: any) {
        super();
        const spec = {...specObj};
        this.responsive = spec.width === 'container' || spec.height === 'container';
        this.runResult = this.outputEl.then(targetEl => {
            if (spec?.data?.values instanceof DataStream) {
                const dataStream: DataStream = spec.data.values;
                delete spec.data.values;
                return embed(targetEl, spec).then(plot =>
                    dataStream
                        .useUnsafeLongs()
                        .batch(500)
                        .limit(2000)
                        .to(batch => plot.view.insert(spec.data.name, batch).runAsync())
                        .run()
                        .then(_ => plot.view.resize().runAsync())
                        .then(_ => plot)
                )
            } else {
                return embed(targetEl, spec);
            }
        });
        this.deferredOutput = this.runResult.then(VegaClientResult.plotToOutput);
    }

    static plotToOutput(plot: Result): Promise<Output> {
        const fromDataURL = (dataURL: string) => {
            const el = document.createElement('div');
            const img = document.createElement('img');
            img.setAttribute('src', dataURL);
            el.appendChild(img);
            const html = el.innerHTML;
            return new Output("text/html", splitOutput(html));
        };

        return new Promise<Output>(resolve => {
            const spec = deepCopy(plot.spec);
            const name = (spec.data as any).name;
            const values = plot.view.data(name);
            spec.data = {
                values: values
            };
            resolve(new Output("application/vnd.vegalite.v4", JSON.stringify(spec).split(/\n/)))
        }).catch(
            _ => plot.view.toCanvas()
                .then(canvas => canvas.toDataURL("image/png"))
                .then(fromDataURL)
                .catch(_ => plot.view.toSVG().then(svgStr => new Output("image/svg+xml", splitOutput(svgStr))))
        );
    }

    toOutput(): Promise<Output> {
        // const el = document.createElement("div");
        // return this.run(el).then(VegaClientResult.plotToOutput);
        return this.deferredOutput;
    }

    display(targetEl: HTMLElement) {
        const el = div([], []);
        if (this.responsive) {
            el.style.display = 'block';
        }
        const wrapper = div(['vega-result'], [el]);

        targetEl.appendChild(wrapper);
        this.runWithOutputEl(el);
    }
}

// this fake object will be added to the scope for the given code, as a kind of sandbox
// probably not going to stop any determined hackers, but...
const windowOverride: any = {};
for (let key of Object.keys(window)) {
    windowOverride[key] = undefined;
}
delete windowOverride.console;
Object.freeze(windowOverride);


/**
 * Sanitize a string as a proper Javascript variable name. Variables in other languages can have characters that are
 * not allowed in Javascript, such as `-`.
 *
 * @param variableName
 */
const substitutions = [
    {
        from: "-",
        to: "$dash$"
    }
]
export function sanitizeJSVariable(variableName: string): string {
    return substitutions.reduce((acc, {from, to}) => {
        return acc.replaceAll(from, to)
    }, variableName)
}
