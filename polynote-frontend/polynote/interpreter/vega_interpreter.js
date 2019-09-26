"use strict";

import * as acorn from "acorn"
import {Position, KernelReport, CompileErrors, Output, KernelErrorWithCause, RuntimeError} from "../data/result";
import {DataStream, MIMERepr} from "../data/value_repr";
import embed from "vega-embed";
import {ClientResult} from "../data/result";

export const VegaInterpreter = {

    languageTitle: "Vega spec",
    highlightLanguage: "vega",

    interpret(code, cellContext) {
        code = '(' + code + ')';
        let ast;
        try {
            ast = acorn.parse(code, {sourceType: 'script', ecmaVersion: 6, ranges: true })
        } catch (err) {
            return [new CompileErrors([
                new KernelReport(new Position(cellContext.id, err.pos - 1, err.pos - 1), err.message, 2)
            ])];
        }
        const wrappedCode = `with(window) return ${code};`;
        const availableValues = cellContext.availableValues;
        if (availableValues.hasOwnProperty('window')) {
            delete availableValues['window'];
        }
        const names = ['window', ...Object.keys(availableValues)];
        const fn = new Function(...names, wrappedCode).bind({});

        try {
            const spec = fn(windowOverride, ...Object.values(availableValues));
            // some validation of the spec
            if (!spec.data)
                throw new Error("Spec is missing data attribute");
            if (spec.data instanceof Array) {
                spec.data.forEach(data => {
                    if (!data.name) {
                        throw new Error("Spec data attribute is missing name property");
                    }
                });
            } else if (!spec.data.name) {
                throw new Error("Spec data attribute is missing name property");
            }

            if (!spec["$schema"]) {
                spec["$schema"] = 'https://vega.github.io/schema/vega-lite/v3.json';
            }

            return [new VegaClientResult(spec)];
        } catch (err) {
            return [RuntimeError.fromJS(err)];
        }
    }


};

export class VegaClientResult extends ClientResult {
    constructor(spec) {
        super();
        this.spec = spec;
    }

    setPlot(plot) {
        if (!this.running) {
            this.running = Promise.resolve(plot);
        } else {
            throw new Error("Plot is already running");
        }
    }

    run(targetEl) {
        const spec = this.spec;

        if (this.running) {
            return this.running;
        }

        let dataStream;

        if (spec.data.values instanceof DataStream) {
            dataStream = spec.data.values;
            delete spec.data.values;
        }

        if (dataStream) {
            this.running = embed(targetEl, spec).then(plot =>
                dataStream
                    .batch(500)
                    .to(batch => plot.view.insert(spec.data.name, batch).runAsync())
                    .run()
                    .then(_ => plot.view.resize().runAsync())
                    .then(_ => plot)
            )
        } else {
            this.running = embed(targetEl, spec);
        }

        return this.running;
    }

    display(targetEl, cell) {
        this.run(targetEl).catch(err => cell.setRuntimeError(RuntimeError.fromJS(err)));
    }

    static plotToOutput(plot) {
        const fromDataURL = (dataURL) => {
            const el = document.createElement('div');
            const img = document.createElement('img');
            img.setAttribute('src', dataURL);
            el.appendChild(img);
            const html = el.innerHTML;
            return new Output("text/html", html);
        };

        return plot.view.toCanvas()
            .then(canvas => canvas.toDataURL("image/png"))
            .then(fromDataURL)
            .catch(_ => plot.toSVG().then(svgStr => new Output("image/svg", svgStr)))
    }

    toOutput() {
        const fromDataURL = (dataURL) => {
            const el = document.createElement('div');
            const img = document.createElement('img');
            img.setAttribute('src', dataURL);
            el.appendChild(img);
            const html = el.innerHTML;
            return new Output("text/html", html);
        };

        return this.run().then(VegaClientResult.plotToOutput);
    }
}

// this fake object will be added to the scope for the given code, as a kind of sandbox
// probably not going to stop any determined hackers, but...
const windowOverride = {};
for (let key of Object.keys(window)) {
    windowOverride[key] = undefined;
}
delete windowOverride.console;
Object.freeze(windowOverride);

