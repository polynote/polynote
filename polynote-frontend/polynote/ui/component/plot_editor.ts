'use strict';


import {div, button, iconButton, h4, TagElement} from '../util/tags'
import {
    BoolType,
    ByteType, DataType,
    DateType, DoubleType,
    FloatType,
    IntType,
    LongType,
    OptionalType,
    ShortType,
    StringType, StructField,
    TimestampType
} from "../../data/data_type";
import {FakeSelect} from "./fake_select";
import {fakeSelectElem, span, textbox} from "../util/tags";
import {SocketSession} from "../../comms";
import {GroupAgg, ModifyStream, ReleaseHandle, TableOp} from "../../data/messages";
import {Pair} from "../../data/codec";
import {DataStream, StreamingDataRepr} from "../../data/value_repr";
import embed, {Result as VegaResult} from "vega-embed";
import {UIMessageTarget} from "../util/ui_event";
import {Cell, CodeCell} from "./cell";
import {VegaClientResult} from "../../interpreter/vega_interpreter";
import {ClientResult, Output} from "../../data/result";
import {CellMetadata} from "../../data/data";
import {CurrentNotebook} from "./current_notebook";


function isDimension(dataType: DataType): boolean {
    if (dataType instanceof OptionalType) {
      return isDimension(dataType.element);
    }
    return (
        dataType === ByteType ||
        dataType === BoolType ||
        dataType === ShortType ||
        dataType === IntType ||
        dataType === LongType ||
        dataType === StringType ||
        dataType === DateType ||
        dataType === TimestampType
    )
}

type MeasureEl = TagElement<"div"> & {field: StructField, selector: FakeSelect};
type MeasureConfig = {
    field: StructField,
    agg?: string
}

function measures(field: StructField): MeasureEl[] {
    let dataType = field.dataType;
    if (dataType instanceof OptionalType) {
        dataType = dataType.element;
    }

    if (
        dataType === ByteType ||
        dataType === ShortType ||
        dataType === IntType ||
        dataType === LongType ||
        dataType === FloatType ||
        dataType === DoubleType
    ) {
        const selector = new FakeSelect(fakeSelectElem(['choose-measure'], [
            button(['selected'], {value: 'mean'}, ['Mean']),
            button([], {value: 'count'}, ['Count']),
            button([], {value: 'quartiles'}, ['Quartiles'])
        ]));

        return [div(['measure', 'selected-measure'], [
            div(['choose-measure'], [
                selector.element
            ]),
            span(['measure-name'], field.name)
        ]).attr('draggable', true).withKey('field', field).withKey('selector', selector) as MeasureEl];
    } else return [];
}

function dimensionType(dataType: DataType): 'nominal' | 'ordinal' | 'quantitative' {
    if (dataType instanceof OptionalType) return dimensionType(dataType.element);
    if (dataType === StringType || dataType === BoolType) return 'nominal';
    if (dataType === DoubleType) return 'quantitative';
    return 'ordinal';
}

type SpecFun = ((this: PlotEditor, plotType: string, xField: StructField, yMeas: MeasureConfig | MeasureConfig[]) => {}) & {
    rawFields?: boolean,
    allowedAggregates?: string[],
    allAggregates?: boolean,
    singleMeasure?: boolean
};

export class PlotEditor extends EventTarget {
    private fields: StructField[];
    container: TagElement<"div">;
    private plotTypeSelector: FakeSelect;
    private specType: SpecFun;
    readonly el: TagElement<"div">;
    private controls: TagElement<"div">;
    private plotWidthInput: TagElement<"input">;
    private plotHeightInput: TagElement<"input">;
    readonly plotOutput: TagElement<"div">;
    private saveButton: TagElement<"button">;
    private runButton: TagElement<"button">;
    private cancelButton: TagElement<"button">;
    private currentStream?: DataStream;
    private plotArea: TagElement<"div">;
    private plotTitle: TagElement<"input">;
    private xAxisDrop: TagElement<"div">;
    readonly xTitle: TagElement<"input">;
    private yAxisDrop: TagElement<"div">;
    readonly yTitle: TagElement<"input">;
    private draggingEl: MeasureEl | null;
    private rawFields: boolean;
    private measureSelectors: MeasureEl[];
    private xDimension: StructField;
    private yMeasures: MeasureConfig[];
    private spec: any;
    private plot: VegaResult;

    constructor(readonly repr: StreamingDataRepr, readonly path: string, readonly name: string, readonly sourceCell: number, readonly plotSavedCb?: () => void) {
        super();
        this.fields = repr.dataType.fields;

        if (!SocketSession.get.isOpen) {
            this.container = div(['plot-editor-container', 'disconnected'], [
                "Not connected to server – must be connected in order to plot."
            ]);
            return;
        }

        this.plotTypeSelector = new FakeSelect(fakeSelectElem(['plot-type-selector'], [
            button(['selected'], {value: 'bar'}, ['Bar']),
            button([], {value: 'line'}, ['Line']),
            button([], {value: 'xy'}, ['XY Scatter']),
            button([], {value: 'boxplot'}, ['Box Plot'])
        ]));

        this.specType = normalSpec;

        this.el = div(['plot-editor'], [
            this.controls = div(['left-controls'], [
                h4(['plot-type-title'], ['Plot type']),
                this.plotTypeSelector.element,
                h4(['plot-size-title'], ['Size']),
                div(['plot-size'], [
                    this.plotWidthInput = textbox(['plot-width'], 'Width', "960").attr("maxLength", "4").change(evt => this.plotOutput.style.width = parseInt((evt.target as TagElement<"input">).value, 10) + 'px'),
                    span([],'⨉'),
                    this.plotHeightInput = textbox(['plot-height'], 'Height', "480").change(evt => this.plotOutput.style.height = parseInt((evt.target as TagElement<"input">).value, 10) + 'px')
                ]),
                h4(['dimension-title'], ['Dimensions', iconButton(['add', 'add-measure'], 'Add dimension', '', 'Add').click(_ => this.showAddDimension())]),
                div(['dimension-list'], this.listDimensions()),
                h4(['measure-title'], ['Measures', iconButton(['add', 'add-measure'], 'Add measure', '', 'Add').click(_ => this.showAddMeasure())]),
                div(['measure-list'], this.listMeasures()),
                h4(['numeric-field-title'], ['Fields']),
                div(['numeric-field-list'], this.listNumerics()),
                div(['control-buttons'], [
                    this.saveButton = button(['save'], {}, [
                        span(['fas'], ''),
                        'Save'
                    ]).click(_ => this.savePlot()),
                    this.runButton = button(['plot'], {}, [
                        span(['fas'], ''),
                        'Plot'
                    ]).click(_ => this.runPlot()),
                    this.cancelButton = button(['cancel'], {}, [
                        span(['fas'], ""),
                        'Cancel'
                    ]).click(_ => this.abortPlot())
                ])
            ]),
            this.plotArea = div(['plot-area'], [
                this.plotOutput = div(['plot-output'], [
                    div(['plot-title'], [
                       this.plotTitle = textbox([], 'Plot title', '')
                    ]),
                    this.xAxisDrop = div(['x-axis-drop'], [span(['label'], [
                        this.xTitle = textbox([], 'Enter an axis title', ''),
                        span(['placeholder'], ['Drop X-axis dimension here'])
                    ])]),
                    this.yAxisDrop = div(['y-axis-drop'], [span(['label'], [
                        span(['placeholder'], ['Drop Y-axis measure(s) here']),
                        this.yTitle = textbox([], 'Enter an axis title', '')
                    ])]),
                    div(['plot-embed'], [])
                ])
            ])
        ]);

        this.container = div(['plot-editor-container'], [this.el]);

        this.saveButton.style.display = 'none';

        this.plotOutput.style.width = '960px';
        this.plotOutput.style.height = '480px';

        this.plotTypeSelector.addListener(() => this.onPlotTypeChange());

        this.el.addEventListener('dragstart', evt => {
           this.draggingEl = evt.target as MeasureEl;
        });

        this.el.addEventListener('dragend', evt => {
           this.xAxisDrop.classList.remove('drop-ok', 'drop-disallowed');
           this.yAxisDrop.classList.remove('drop-ok', 'drop-disallowed');
           this.draggingEl = null;
        });

        this.el.addEventListener('dragenter', evt => {
           this.yAxisDrop.classList.remove('drop-ok', 'drop-disallowed');
           this.xAxisDrop.classList.remove('drop-ok', 'drop-disallowed');
        });

        const attachAxisListener = (axisEl: HTMLElement, setField: (from: MeasureEl) => void, axisType: () => string) => {
            axisEl.addEventListener('dragenter', evt => {
                evt.stopPropagation();
                if (this.draggingEl && this.draggingEl.classList.contains(axisType())) {
                    axisEl.classList.add('drop-ok');
                } else {
                    axisEl.classList.add('drop-disallowed');
                }
            });

            axisEl.addEventListener('dragover', evt => {
                if (this.draggingEl && this.draggingEl.classList.contains(axisType())) {
                    evt.preventDefault();
                }
            });

            axisEl.addEventListener('drop', evt => {
                if (this.draggingEl) setField(this.draggingEl);
                axisEl.classList.remove('drop-ok', 'drop-disallowed');
            });
        };

        attachAxisListener(this.xAxisDrop, m => this.setXField(m), () => this.correctXType);
        attachAxisListener(this.yAxisDrop, m => this.addYField(m), () => this.correctYType);
        this.onPlotTypeChange();
    }

    get correctYType() {
        if (this.rawFields) return 'numeric';
        return 'measure';
    }

    get correctXType() {
        if (this.rawFields) return 'numeric';
        return 'dimension';
    }

    showAddMeasure() {
        // TODO - show a UI to let you explore measures you can use in more detail
    }

    showAddDimension() {
        // TODO - show a UI to let you
    }

    listDimensions() {
        return this.fields.filter(field => isDimension(field.dataType)).map(
            field => div(['dimension'], [
                field.name,
                ` (${(field.dataType.constructor as typeof DataType).typeName(field.dataType)})`]
            ).withKey('field', field).attr('draggable', true)
        )
    }

    listNumerics() {
        return this.fields.filter(field => field.dataType.isNumeric).map(
            field => div(['numeric'], [
                field.name,
                ` (${(field.dataType.constructor as typeof DataType).typeName(field.dataType)})`]
            ).withKey('field', field).attr('draggable', true)
        )
    }

    listMeasures() {
        this.measureSelectors = this.fields.flatMap(field => measures(field));
        return this.measureSelectors;
    }

    onPlotTypeChange() {
        function showDefaultMeasures(selector: FakeSelect) {
            selector.showAllOptions();
            selector.hideOption('quartiles');
        }

        this.measureSelectors.forEach(el => delete el.style.display);
        this.controls.classList.remove('numeric-fields');
        this.rawFields = false;

        const plotType = this.plotTypeSelector.value;
        if (specialSpecs[plotType]) {
            const specType = specialSpecs[plotType];
            this.specType = specType;

            if (specType.rawFields) {
                this.controls.classList.add('numeric-fields');
                this.rawFields = true;
            } else if (specType.allowedAggregates) {
                this.measureSelectors.forEach(el => {
                    delete el.style.display;
                    const sel = el.selector;
                    sel.options.forEach((opt, idx) => {
                        if (specType.allowedAggregates!.indexOf(opt.value) < 0) {
                            sel.hideOption(idx);
                        } else {
                            sel.showOption(idx);
                        }
                    });
                })
            } else if (!specType.allAggregates) {
                this.measureSelectors.forEach(el => showDefaultMeasures(el.selector));
            } else {
                this.measureSelectors.forEach(el => el.selector.showAllOptions());
            }
        } else {
            this.measureSelectors.forEach(el => showDefaultMeasures(el.selector));
        }
        // TODO - evict any measures that aren't allowed by this plot type
        // TODO - allow dimension vs dimension plot if the plot type allows it
    }

    getTableOps() {
        // TODO - for multiple mods, use diff from last mod
        const ops: TableOp[] = [];
        if (this.rawFields) {
            return ops;
        }

        if (this.xDimension && this.yMeasures && this.yMeasures.length) {
            ops.push(
                new GroupAgg(
                    [this.xDimension.name],
                    this.yMeasures.map(meas => new Pair(meas.field.name, meas.agg!)) // if this.rawFields is false, meas.agg is definitely defined.
                )
            );
        }

        return ops;
    }

    setXField(from: MeasureEl) {
        const field = from.field;
        this.xDimension = field;
        this.xAxisDrop.classList.add('nonempty');
        const label = this.xAxisDrop.querySelector('.label')!;
        [...label.querySelectorAll('.numeric, .dimension')].forEach(node => node.parentNode!.removeChild(node));
        label.appendChild(span([this.correctXType], [field.name]));
    }

    addYField(from: MeasureEl) {
        if (!this.yMeasures) {
            this.yMeasures = [];
        }

        if (this.rawFields) {
            this.yAxisDrop.classList.add('nonempty');
            this.yAxisDrop.appendChild(span([this.correctYType], [from.field.name]));
            this.yMeasures.push({
                field: from.field
            });

        } else if (from.classList.contains('selected-measure')) {
            const selector = from.selector;
            const field = from.field;
            const measureConfig = {
                field,
                agg: selector.value
            };

            this.yMeasures.push(measureConfig);

            const label = span(
                ['measure'], [
                    `${selector.value}(${field.name})`,
                    iconButton(['remove'], 'Remove', '', 'X').click(_ => {
                        const idx = this.yMeasures.indexOf(measureConfig);
                        this.yMeasures.splice(idx, 1);
                        label.parentNode!.removeChild(label);
                        if (!this.yMeasures.length) {
                            this.yAxisDrop.classList.remove('nonempty');
                        }
                    })
                ]
            );

            this.yAxisDrop.classList.add('nonempty');
            const target = this.yAxisDrop.querySelector('.label')!;
            target.insertBefore(label, target.querySelector('input'));
        }
    }

    getSpec(plotType: string) {
        if(specialSpecs[plotType]) {
            const specFn = specialSpecs[plotType];
            let measures = this.yMeasures;
            if (specFn.allowedAggregates) {
                measures = measures.filter(measure => specFn.allowedAggregates!.indexOf(measure.agg!) >= 0);
            }
            if (!measures.length) {
                throw `No usable measures for ${plotType}`;
            }
            if (specFn.singleMeasure) {
                measures = [measures[0]]
            }
            return specFn.call(this, plotType, this.xDimension, measures);
        } else {
            return normalSpec.call(this, plotType, this.xDimension, this.yMeasures);
        }
    }

    runPlot() {
        //this.runButton.disabled = true;
        this.el.classList.add('running');
        this.saveButton.style.display = 'none';
        if (this.currentStream) {
            throw new Error("Plot can't be run when a previous plot stream is already running");
        }

        const stream = this.currentStream = new DataStream(this.path, this.repr, this.getTableOps()).batch(500);

        // TODO: multiple Ys
        // TODO: encode color
        // TODO: box plot has to be specially handled in order to pre-aggregate, https://github.com/vega/vega-lite/issues/4343
        const plotType = this.plotTypeSelector.value;

        const spec = this.getSpec(plotType);

        if (this.plotTitle.value !== '') {
            spec.title = this.plotTitle.value;
        }

        spec.autosize = 'fit';
        spec.width = +(this.plotWidthInput.value);
        spec.height = +(this.plotHeightInput.value);

        this.spec = spec;

        embed(
            this.plotOutput.querySelector('.plot-embed') as HTMLElement,
            spec
        ).then(plot => {
            stream
                .to(batch => plot.view.insert(this.name, batch).runAsync())
                .run()
                .then(_ => {
                    plot.view.resize().runAsync();
                    this.saveButton.style.display = '';
                    this.plotOutput.style.width = (this.plotOutput.querySelector('.plot-embed') as HTMLElement).offsetWidth + "px";
                    this.plotOutput.style.height = (this.plotOutput.querySelector('.plot-embed') as HTMLElement).offsetHeight + "px";
                    this.el.classList.remove('running');
                    this.plot = plot;
                    this.currentStream = undefined;
                    //this.session.send(new ReleaseHandle(this.path, StreamingDataRepr.handleTypeId, repr.handle));
                }).catch(reason => {
                    this.handleError(reason);
                });
        });
    }

    handleError(err: any) {
        this.abortPlot();
        // TODO: display error to the user
    }

    abortPlot() {
        if (this.currentStream) {
            this.currentStream.abort();
        }
        this.currentStream = undefined;
        this.el.classList.remove('running');
    }

    savePlot() {
        const spec = this.spec;
        this.spec.data.values = '$DATA_STREAM$';
        let content = JSON.stringify(this.spec, null, 2);
        const ops = this.getTableOps();
        let streamSpec = this.name;
        ops.forEach(op => {
            if (op instanceof GroupAgg) {
                const aggSpecs = op.aggregations.map(pair => {
                    const obj: Record<string, string> = {};
                    obj[pair.first] = pair.second;
                    return obj;
                });
                streamSpec = `${streamSpec}.aggregate(${JSON.stringify(op.columns)}, ${JSON.stringify(aggSpecs)})`;
            } // others TODO
        });
        content = content.replace('"$DATA_STREAM$"', streamSpec);
        const mkCell = (cellId: number) => new CodeCell(cellId, `(${content})`, 'vega', this.path, new CellMetadata(false, true, false));
        VegaClientResult.plotToOutput(this.plot).then(output => {
            CurrentNotebook.get.insertCell("below", this.sourceCell, mkCell, [output], (cell: CodeCell) => {
                cell.displayResult(new PlotEditorResult(this.plotOutput.querySelector('.plot-embed') as TagElement<"div">, output))
            });

            if (this.plotSavedCb) this.plotSavedCb()
        });
    }

}

function normalSpec(this: PlotEditor, plotType: string, xField: StructField, yMeas: MeasureConfig[] | MeasureConfig) {
    const spec: any = {
        $schema: 'https://vega.github.io/schema/vega-lite/v3.json',
        data: {name: this.name},
        mark: plotType,
        encoding: {
            x: {
                field: xField.name,
                type: dimensionType(xField.dataType)
            }
        },
        width: this.plotOutput.offsetWidth - 100,
        height: this.plotOutput.offsetHeight - 100
    };

    if (yMeas instanceof Array && yMeas.length === 1) {
        yMeas = yMeas[0];
    }

    if (yMeas instanceof Array) {
        spec.transform = [{
            fold: yMeas.map(measure => measure.agg ? `${measure.agg}(${measure.field.name})` : measure.field.name)
        }];
        spec.encoding.y = {
            field: 'value'
        };
        spec.encoding.color = {
            field: 'key',
            type: 'nominal'
        };
    } else {
        spec.encoding.y = {
            field: yMeas.agg ? `${yMeas.agg}(${yMeas.field.name})` : yMeas.field.name,
            type: 'quantitative'
        };
    }

    if (this.yTitle.value !== '') {
        spec.encoding.y.axis = { title: this.yTitle.value }
    }

    if (this.xTitle.value !== '') {
        spec.encoding.x.axis = { title: this.xTitle.value }
    }

    return spec;
}

const specialSpecs: Record<string, SpecFun> = {
    boxplot: boxplotSpec,
    line: lineSpec,
    xy: xySpec
};

function xySpec(this: PlotEditor, plotType: string, xField: StructField, yMeas: MeasureConfig | MeasureConfig[]) {
    return normalSpec.call(this, 'point', xField, yMeas);
}

xySpec.rawFields = true;
xySpec.singleMeasure = true;
xySpec.noAggregates = true;

// we kind of have to roll our own boxplot layering, because we are pre-aggregating the data (see https://github.com/vega/vega-lite/issues/4343)
// The way to construct it was taken from https://vega.github.io/vega-lite/docs/boxplot.html
// it's essentially what an actual box plot expands to.
function boxplotSpec(this: PlotEditor, plotType: string, xField: StructField, yMeas: MeasureConfig | MeasureConfig[]) {
    // TODO: can we allow multiple series of boxes? Does `fold` support a struct like this?
    if (yMeas instanceof Array) {
        yMeas = yMeas[0];
    }
    const yName = `quartiles(${yMeas.field.name})`;
    const yTitle = this.yTitle.value || yName;
    const x: any = { field: xField.name, type: dimensionType(xField.dataType) };
    if (this.xTitle.value) {
        x.axis = { title: this.xTitle.value }
    }
    const size = 14;
    return {
        $schema: 'https://vega.github.io/schema/vega-lite/v3.json',
        data: {name: this.name},
        width: this.plotOutput.offsetWidth - 100,
        height: this.plotOutput.offsetHeight - 100,
        layer: [
            {
                // lower whisker
                mark: {type: "rule", style: "boxplot-rule"},
                encoding: {
                    x,
                    y: {
                        field: `${yName}.min`,
                        type: 'quantitative',
                        axis: {title: yTitle},
                        scale: {zero: false } // TODO: configurable
                    },
                    y2: {
                        field: `${yName}.q1`
                    }
                }
            },
            {
                // upper whisker
                mark: {type: "rule", style: "boxplot-rule"},
                encoding: {
                    x,
                    y: {
                        field: `${yName}.q3`,
                        type: 'quantitative'
                    },
                    y2: {
                        field: `${yName}.max`
                    }
                }
            },
            {
                // box
                mark: {type: "bar", size, style: "boxplot-box"},
                encoding: {
                    x,
                    y: {
                        field: `${yName}.q1`,
                        type: 'quantitative'
                    },
                    y2: {
                        field: `${yName}.q3`
                    }
                }
            },
            {
                // median tick
                mark: {
                    color: 'white',
                    type: 'tick',
                    size,
                    orient: 'horizontal',
                    style: 'boxplot-median'
                },
                encoding: {
                    x,
                    y: {
                        field: `${yName}.median`,
                        type: 'quantitative'
                    }
                }
            },
            {
                // mean point
                mark: {
                    color: 'black',
                    type: 'point',
                    size: size / 2
                },
                encoding: {
                    x,
                    y: {
                        field: `${yName}.mean`,
                        type: 'quantitative'
                    }
                }
            }
        ]
    };
}

boxplotSpec.allowedAggregates = ['quartiles'];
boxplotSpec.singleMeasure = true;

function lineSpec(this: PlotEditor, plotType: string, xField: StructField, yMeas: MeasureConfig | MeasureConfig[]) {
    if (yMeas instanceof Array && yMeas.length === 1) {
        yMeas = yMeas[0];
    }

    let yField = "";
    let transform: any[] = [];
    let encodeColor: any = false;
    let confidenceBands = false;
    let layer: any[] = [];

    if (yMeas instanceof Array) {
        transform = [{
            fold: yMeas.map(measure => `${measure.agg}(${measure.field.name})`)
        }];
        encodeColor = {
            field: 'key',
            type: 'nominal'
        };
        yField = 'value';

        confidenceBands = yMeas.findIndex(meas => meas.agg === 'quartiles') >= 0;
    } else {
        yField = `${yMeas.agg}(${yMeas.field.name})`;
        confidenceBands = yMeas.agg === 'quartiles';
    }

    if (confidenceBands) {
        layer = [
            // TODO: are min/max useful? Or just too much noise?
            /*{
                mark: 'area',
                encoding: {
                    x: {
                        field: xField.name,
                        type: 'ordinal'
                    },
                    y: {
                        field: `${yField}.min`,
                        type: 'quantitative',
                        //axis:  {title: yField}
                    },
                    y2: {
                        field: `${yField}.max`
                    },
                    opacity: {value: 0.1}
                },
            },*/
            {
                mark: 'area',
                encoding: {
                    x: {
                        field: xField.name,
                        type: 'ordinal',
                        axis: { title: this.xTitle.value || xField.name }
                    },
                    y: {
                        field: `${yField}.q1`,
                        type: 'quantitative',
                        axis: { title: this.yTitle.value || yField },
                        scale: {zero: false } // TODO: configurable
                    },
                    y2: {
                        field: `${yField}.q3`
                    },
                    opacity: {value: 0.3}
                },
            },
            {
                mark: 'line',
                encoding: {
                    x: {
                        field: xField.name,
                        type: 'ordinal'
                    },
                    y: {
                        field: `${yField}.median`,
                        type: 'quantitative',
                        axis: yField
                    }
                },
            },
            {
                mark: 'line',
                encoding: {
                    x: {
                        field: xField.name,
                        type: 'ordinal'
                    },
                    y: {
                        field: `${yField}`,
                        type: 'quantitative',
                        axis: yField
                    }
                },
            }
        ];
    } else {
        layer = [
            {
                mark: 'line',
                encoding: {
                    x: {
                        field: xField.name,
                        type: 'ordinal'
                    },
                    y: {
                        field: yField,
                        type: 'quantitative'
                    }
                },
            }
        ];
    }

    if (encodeColor) {
        layer.forEach(l => l.encoding.color = encodeColor);
    }

    const spec = {
        $schema: 'https://vega.github.io/schema/vega-lite/v3.json',
        data: {name: this.name},
        width: this.plotOutput.offsetWidth - 100,
        height: this.plotOutput.offsetHeight - 100,
        transform,
        layer
    };

    return spec;
}

lineSpec.allAggregates = true;

class PlotEditorResult extends ClientResult {
    constructor(readonly plotEl: TagElement<"div">, readonly output: Output) {
        super();
    }

    display(targetEl: HTMLElement, cell: Cell) {
        targetEl.appendChild(this.plotEl);
    }

    toOutput() {
        return Promise.resolve(this.output);
    }
}