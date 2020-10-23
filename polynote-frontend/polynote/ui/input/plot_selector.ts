import {button, div, dropdown, h3, h4, iconButton, label, span, TagElement, textbox} from "../tags";
import {
    BoolType,
    ByteType,
    DataType, DateType,
    DoubleType, IntType, LongType, NumericTypes,
    OptionalType,
    ShortType,
    StringType, StructField,
    StructType, TimestampType
} from "../../data/data_type";
import {DataStream} from "../../messaging/datastream";
import {
    LayerSpec,
    TopLevelSpec
} from "vega-lite/build/src/spec";
import {Encoding} from "vega-lite/build/src/encoding";
import {TopLevelUnitSpec} from "vega-lite/src/spec/unit";
import {Mark} from "vega-lite/build/src/mark";
import match from "../../util/match";
import {GroupAgg, Select, TableOp} from "../../data/messages";
import {Pair} from "../../data/codec";
import {deepEquals} from "../../util/helpers";

export interface DimensionAxis {
    title?: string
    field: string
}

export interface PlotSeries {
    field: string
    aggregation?: string
}

export interface MeasureAxis {
    title?: string
    series: PlotSeries[]
}

export interface BasicPlot {
    type: Mark
    x: DimensionAxis
    y: MeasureAxis
}

export interface BarPlot extends BasicPlot {
    type: "bar"
    stacked?: boolean
}

export interface LinePlot extends BasicPlot {
    type: "line"
    point?: boolean
    confidence?: boolean
}

export interface StackedAreaPlot extends BasicPlot {
    type: "area"
}

export interface XYScatter {
    type: "xy"
    x: DimensionAxis
    y: DimensionAxis
    color?: DimensionAxis
    bubble?: DimensionAxis
}

export interface BoxPlot {
    type: "boxplot"
    x: DimensionAxis
    y: DimensionAxis
}

export interface PiePlot {
    type: "pie"
    x: DimensionAxis
    y: DimensionAxis
}

export interface Histogram {
    type: "histogram"
    x: DimensionAxis
    binCount: number
}

export type Plot = BarPlot | LinePlot | StackedAreaPlot | XYScatter | BoxPlot | PiePlot | Histogram
export interface PlotDefinition {
    value: string
    title?: string
    plot?: Plot
    forceZero?: boolean
}

export const PlotDefinition = {
    empty(value: string): PlotDefinition {
        return { value }
    }
}

export function parsePlotDefinition(code: string): PlotDefinition {
    return JSON.parse(code)
}

export function savePlotDefinition(plotDef: PlotDefinition) {
    return JSON.stringify(plotDef)
}

export function plotToVega(plotDef: PlotDefinition, schema: StructType): TopLevelSpec {
    if (!plotDef.plot)
        throw new Error("Plot definition is incomplete!");

    const plot: Plot = plotDef.plot;
    switch (plot.type) {
        case "bar":     return barPlot(plotDef, plot, schema);
        case "line":    return linePlot(plotDef, plot, schema);
        case "area":    return stackedAreaPlot(plotDef, plot, schema);
        case "xy":      return xyScatterPlot(plotDef, plot, schema);
        case "boxplot": return boxPlot(plotDef, plot, schema);
        // TODO: pie
        // TODO: histogram
        default:        throw new Error(`Unsupported plot type '${plot.type}`)
    }
}

function tableOps(plotDef: PlotDefinition): TableOp[] {
    const plot = plotDef.plot;
    if (!plot)
        throw new Error("Plot definition is incomplete!");

    switch (plot.type) {
        case "bar":
        case "line":
        case "area":
            return [
                new GroupAgg([plot.x.field], plot.y.series.flatMap(series => series.aggregation ? [new Pair(series.field, series.aggregation)] : [])),
                new Select([plot.x.field, ...plot.y.series.map(seriesName)])
            ];

        case "xy":
            const selectFields = [plot.x.field, plot.y.field];
            if (plot.bubble)
                selectFields.push(plot.bubble.field);
            if (plot.color)
                selectFields.push(plot.color.field);
            return [new Select(selectFields)];

        case "boxplot":
            return [
                new GroupAgg([plot.x.field], [new Pair(plot.y.field, "quartiles")]),
                new Select([plot.x.field, `quartiles(${plot.y.field})`])
            ]

        // TODO: pie
        // TODO: histogram
        default: throw new Error(`Unsupported plot type '${plot.type}`)
    }
}

export function plotToVegaCode(plotDef: PlotDefinition, schema: StructType): string {
    const spec = plotToVega(plotDef, schema) as any;
    spec.data.values = '$DATA_STREAM$';
    const ops = tableOps(plotDef);
    let streamSpec = plotDef.value;
    ops.forEach(op => streamSpec = op.streamCode(streamSpec));
    return JSON.stringify(spec).replace('"$DATA_STREAM"', streamSpec);
}

function seriesName(series: PlotSeries) {
    return series.aggregation ? `${series.aggregation}(${series.field})` : series.field
}

function dimensionType(dataType: DataType): 'nominal' | 'ordinal' | 'quantitative' {
    if (dataType instanceof OptionalType) return dimensionType(dataType.element);
    if (dataType === StringType || dataType === BoolType) return 'nominal';
    if (dataType === DoubleType) return 'quantitative';
    return 'ordinal';
}

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

function basicPlot(plotDef: PlotDefinition, plot: BasicPlot, schema: StructType, xType?: 'nominal' | 'ordinal' | 'quantitative'): TopLevelUnitSpec {
    const schemaField = schema.fields.find(field => field.name === plot.x.field)
    if (!schemaField) {
        throw new Error(`Field ${plot.x.field} is not in the schema`);
    }

    const xAxisType = xType || dimensionType(schemaField.dataType);

    const result: TopLevelSpec & {encoding: Encoding<string>} = {
        $schema: 'https://vega.github.io/schema/vega-lite/v4.json',
        title: plotDef.title,
        data: {name: plotDef.value},
        mark: plot.type,
        encoding: {
            x: {
                field: plot.x.field,
                type: xAxisType,
                axis: {
                    title: plot.x.title
                }
            },
            y: {
                axis: {
                    title: plot.y.title
                }
            }
        }
    };

    if (plot.y.series.length > 1) {
        result.transform = [{
           fold: plot.y.series.map(seriesName)
        }];
        result.encoding.y = {
            field: 'value',
            type: 'quantitative'
        };
        result.encoding.color = {
            field: 'key',
            type: 'nominal'
        };
    } else {
        const s = plot.y.series[0];
        result.encoding.y = {
            field: seriesName(s),
            type: 'quantitative'
        };
    }

    return result;
}

function barPlot(plotDef: PlotDefinition, plot: BarPlot, schema: StructType) {
    return basicPlot(plotDef, plot, schema);
}

function linePlot(plotDef: PlotDefinition, plot: LinePlot, schema: StructType): TopLevelSpec {
    const result = basicPlot(plotDef, plot, schema);
    if (plot.point) {
        result.mark = {
            type: "line",
            point: true
        }
    }
    if (plot.confidence) {
        const yAxis = { title: plot.y.title || "value" }
        const resultCopy = {...result};
        delete resultCopy.mark;
        if (resultCopy.encoding)
            delete resultCopy.encoding.y;

        const layerResult: LayerSpec = {
            ...resultCopy,
            transform: [{
                fold: plot.y.series.map(seriesName)
            }],
            layer: [
                {
                    mark: 'area',
                    encoding: {
                        x: {
                            field: plot.x.field,
                            type: 'ordinal',
                            axis: { title: plot.x.title || plot.x.field }
                        },
                        y: {
                            field: `value.q1`,
                            type: 'quantitative',
                            axis: yAxis,
                            scale: { zero: !!plotDef.forceZero }
                        },
                        y2: {
                            field: `value.q3`
                        },
                        opacity: {value: 0.3}
                    },
                },
                {
                    mark: result.mark,
                    encoding: {
                        x: {
                            field: plot.x.field,
                            type: 'ordinal'
                        },
                        y: {
                            field: `value.median`,
                            type: 'quantitative',
                            axis: yAxis
                        }
                    },
                }]
        };
        return layerResult;
    }
    return result;
}

function stackedAreaPlot(plotDef: PlotDefinition, plot: StackedAreaPlot, schema: StructType) {
    return basicPlot(plotDef, plot, schema);
}

function xyScatterPlot(plotDef: PlotDefinition, plot: XYScatter, schema: StructType): TopLevelSpec {
    const result: TopLevelSpec & { encoding: Encoding<string> } = {
        $schema: 'https://vega.github.io/schema/vega-lite/v4.json',
        title: plotDef.title,
        data: {name: plotDef.value},
        mark: "point",
        encoding: {
            x: {
                field: plot.x.field,
                type: "quantitative",
                axis: {
                    title: plot.x.title
                }
            },
            y: {
                field: plot.y.field,
                axis: {
                    title: plot.y.title
                }
            }
        }
    };

    if (plot.color) {
        result.encoding.color = {
            field: plot.color.field,
            type: "nominal"
        }
    }

    if (plot.bubble) {
        result.encoding.size = {
            field: plot.bubble.field,
            type: "quantitative"
        }
    }

    return result;
}

function boxPlot(plotDef: PlotDefinition, plot: BoxPlot, schema: StructType): TopLevelSpec {
    const schemaField = schema.fields.find(field => field.name === plot.x.field)
    if (!schemaField) {
        throw new Error(`Field ${plot.x.field} is not in the schema`);
    }

    // box plot must use quartiles aggregation
    const baseY = `quartiles(${plot.y.field})`;

    return {
        $schema: 'https://vega.github.io/schema/vega-lite/v4.json',
        title: plotDef.title,
        data: {name: plotDef.value},
        encoding: {
            x: {
                field: plot.x.field,
                type: dimensionType(schemaField.dataType),
                axis: {
                    title: plot.x.title
                }
            }
        },
        layer: [
            {
                mark: { type: "rule" },
                encoding: {
                    y: { field: `${baseY}.min`, type: "quantitative", title: plot.y.title, scale: { zero: plotDef.forceZero }},
                    y2: { field: `${baseY}.max` }
                }
            },
            {
                mark: { type: "bar" },
                encoding: {
                    y: { field: `${baseY}.q1`, type: "quantitative" },
                    y2: { field: `${baseY}.q3` }
                }
            },
            {
                mark: { type: "tick", color: "white" },
                encoding: {
                    y: { field: `${baseY}.median`, type: "quantitative"}
                }
            },
            {
                mark: { type: "point", color: "black" },
                encoding: {
                    y: { field: `${baseY}.mean`, type: "quantitative" }
                }
            }
        ]
    };
}

const plotTypes = {
    bar: "Bar",
    line: "Line",
    area: "Stacked area",
    xy: "XY Scatter",
    boxplot: "Box plot",
    pie: "Pie",
    histogram: "Histogram"
}

function deepNumericFields(fields: StructField[], path?: string): StructField[] {
    const currentPath = path ? `${path}.` : "";
    return fields.flatMap(field =>
        match(field.dataType)
            .typed<StructField[]>()
            // TODO: does backend support field traversal like this? Both spark and collections?
            .when(StructType, fields => deepNumericFields(fields, currentPath + field.name))
            .whenP(type => NumericTypes.indexOf(type) >= 0, typ => [new StructField(`${currentPath}${field.name}`, typ)])
            .otherwise([])
    );
}

function deepDimensionFields(fields: StructField[], path?: string): StructField[] {
    const currentPath = path ? `${path}.` : "";
    return fields.flatMap(field =>
        match(field.dataType)
            .typed<StructField[]>()
            // TODO: does backend support field traversal like this? Both spark and collections?
            .when(StructType, fields => deepDimensionFields(fields, currentPath + field.name))
            .whenP(isDimension, typ => [new StructField(`${currentPath}${field.name}`, typ)])
            .otherwise([])
    );
}

const Measures = {
    mean: "Mean",
    sum: "Sum",
    count: "Count"
    // quartiles is now handled separately
}

class MeasurePicker {
    readonly el: HTMLDivElement;
    private readonly popup: HTMLDivElement;
    private readonly button: TagElement<'button'>;
    private readonly doHidePopup = (evt?: any) => {
        if (!evt.key || evt.key === 'Escape')
            this.hidePopup();
    }

    private listeners: ((name: string, measure: string, self: MeasurePicker) => void)[] = [];

    constructor(readonly field: StructField) {
        this.el = div(['measure-picker'], [
            span(['name'], field.name),
            this.button = iconButton(['add'], 'Add', 'plus-circle', 'Add')
                .click(evt => {
                    this.showPopup();
                    evt.stopPropagation();
                    return false;
                })
        ]);

        this.popup = div(['measures-popup', 'dropdown', 'open'], [
            ...Object.entries(Measures).map(
                pair => button([], {name: pair[0]}, pair[1]).click(evt => this.trigger(pair[0]))
            )
        ]);
    }

    dispose() {
        this.listeners = [];
        this.el.innerHTML = "";
    }

    private trigger(measure: string) {
        this.listeners.forEach(fn => fn(this.field.name, measure, this));
    }

    private showPopup() {
        this.hidePopup();
        window.addEventListener('click', this.doHidePopup);
        window.addEventListener('keydown', this.doHidePopup);
        this.el.appendChild(this.popup);
    }

    private hidePopup() {
        if (this.popup.parentNode) {
            window.removeEventListener('click', this.doHidePopup);
            window.removeEventListener('keydown', this.doHidePopup);
            this.popup.parentNode.removeChild(this.popup);
        }
    }

    onSelect(fn: (name: string, measure: string, self: MeasurePicker) => void): MeasurePicker {
        this.listeners.push(fn);
        return this;
    }

    hideMeasure(measure: string) {
        const button = (this.popup.children.namedItem(measure)) as HTMLButtonElement;
        if (button) {
            button.style.display = 'none';
            button.disabled = true;
        }
    }

    showMeasure(measure: string) {
        const button = (this.popup.children.namedItem(measure)) as HTMLButtonElement;
        if (button) {
            button.style.display = '';
            button.disabled = false;
        }
    }
}

class MeasuresUI {
    readonly el: HTMLDivElement;
    private selectedMeasures: PlotSeries[] = [];
    private selectedMeasuresEl: TagElement<"div">;
    private pickers: Record<string, MeasurePicker>;

    private listeners: ((selected: PlotSeries[], added?: PlotSeries, removed?: PlotSeries) => void)[] = [];

    constructor(private availableFields: StructField[]) {
        this.pickers = Object.fromEntries(
            availableFields.map(
                field => [
                    field.name,
                    new MeasurePicker(field).onSelect(
                        (field, measure, picker) => this.addMeasure(field, measure, picker)
                    )
                ]
            )
        );

        this.el = div(['measure-selector'], [
            div(['available-measures'], [
                h4([], "Available fields"),
                div(['measure-pickers'], Object.values(this.pickers).map(picker => picker.el))
            ]),
            div(['selected-measures'], [
                h4([], "Selected measures"),
                this.selectedMeasuresEl = div([], [])
            ])
        ])
    }

    private removeMeasure(field: string, aggregation: string) {
        const index = this.selectedMeasures.findIndex(series => series.field === field && series.aggregation === aggregation);
        if (index >= 0) {
            const [removed] = this.selectedMeasures.splice(index, 1);
            this.selectedMeasuresEl.removeChild(this.selectedMeasuresEl.children[index]);
            this.pickers[field].showMeasure(aggregation);

            this.listeners.forEach(
                fn => fn([...this.selectedMeasures], undefined, removed)
            );
        }
    }

    private addMeasure(field: string, aggregation: string, picker: MeasurePicker) {
        const added = {field, aggregation}
        this.selectedMeasures.push(added);
        this.selectedMeasuresEl.appendChild(
            div(['selected-measure'], [
                span([], `${aggregation}(${field})`),
                iconButton(['remove', 'red'], 'Remove', 'minus-circle-red', "Remove")
                    .click(_ => this.removeMeasure(field, aggregation))
            ])
        );
        picker.hideMeasure(aggregation);
        this.listeners.forEach(
            fn => fn([...this.selectedMeasures], added)
        );
    }

    onChange(fn: (selected: PlotSeries[], added?: PlotSeries, removed?: PlotSeries) => void): MeasuresUI {
        this.listeners.push(fn);
        return this;
    }

    dispose() {
        this.listeners = [];
        for (let picker of Object.keys(this.pickers)) {
            this.pickers[picker].dispose();
        }
        this.el.innerHTML = "";
    }
}

export class PlotSelector {
    readonly el: TagElement<'div'>;
    private state: PlotSelectorState;
    private measuresUI: MeasuresUI;
    private listeners: ((newPlot: PlotDefinition, oldPlot?: PlotDefinition) => any)[] = [];
    //
    // // visible when the X axis is a dimension (i.e. a discrete type, not DoubleType)
    // private xDimensionConfig: TagElement<'div'>;
    //
    // // visible when it's a dimension vs measure(s) plot, e.g. a BasicPlot
    // private ySeriesConfig: TagElement<'div'>;
    //
    // // visible when the X axis is a continuous type (i.e. DoubleType)
    // private xAxisConfig: TagElement<'div'>;
    //
    // // visible when the Y axis can have only one

    constructor(private name: string, schema: StructType, initialState?: PlotDefinition) {
        const dimensionFields = deepDimensionFields(schema.fields);

        if (dimensionFields.length === 0) {
            throw new Error("No possible dimension fields");
        }

        const dimensionOptions = Object.fromEntries(dimensionFields.map(field => [field.name, field.name]));

        const measureFields = deepNumericFields(schema.fields);

        this.state = initialState ? PlotSelectorState.fromPlotDef(initialState, dimensionFields[0].name) : PlotSelectorState.empty(dimensionFields[0].name);

        this.el = div(['plot-selector', this.state.type], [
            h3(['table-name'], ['Plotting ', name]),
            div(['top-tools'], [
                div(['type-selector'], [
                    label([], "Type",
                        dropdown([], plotTypes, this.state.type).onSelect(value => this.setType(value)))
                ]),
                div(['title-input'], [
                    label([], "Title",
                        textbox([], "Plot title", initialState?.value || "")
                            .onValueChange(value => this.state.title = value))
            ])]),
            div(['x-dimension-config'], [
                h3([], "X Dimension"),
                label(['dimension-field'], "Field",
                    dropdown([], dimensionOptions, initialState?.plot?.x.field).onSelect(value => this.state.x.field = value)
                ),
                label(['title'], "Title",
                    textbox([], "Axis title", initialState?.plot?.x.title).onValueChange(value => this.state.x.title = value))
            ]),
            div(['y-series-config'], [
                h3([], "Y Measures"),
                div(['measure-configs'], [
                    this.measuresUI = new MeasuresUI(measureFields).onChange(
                        (selected) => this.updateSeries(selected)
                    )
                ])
            ]),
            div(['x-axis-config'], [
                h3([], 'X Axis')
            ]),
            div(['y-axis-config'], [
                h3([],  'Y Axis'),

            ])
        ]).listener("change", evt => this.viewUpdate())
    }

    private updateSeries(series: PlotSeries[]) {
        if (!this.state.multiY) {
            this.state.multiY = {
                series
            }
        } else {
            this.state.multiY.series = series;
        }
    }

    private viewUpdate() {

    }

    private setType(typ: string) {
        this.el.classList.remove(this.state.type);
        this.state.type = typ;
        this.el.classList.add(typ);
    }

    private setTitle(title: string) {

    }

    private update<A>(fn: () => A): A {
        const oldState = this.state;
        const result = fn();
        if (!deepEquals(this.state, oldState)) {

        }
        return result;
    }

    // private mkPlot(state: PlotSelectorState): PlotDefinition | undefined {
    //     const plotDef: PlotDefinition = {
    //         value: this.name,
    //         title: state.title || undefined,
    //         forceZero: state.forceZero
    //     };
    //
    //     let plot: Plot | undefined;
    //
    //     switch (state.type) {
    //         case "bar":
    //         case "line":
    //         case "area":
    //             if (state.multiY) {
    //                 plot = {
    //                     type: state.type,
    //                     x: state.x,
    //                     y: state.multiY
    //                 }
    //             }
    //             break;
    //         case "xy":
    //         case "boxplot":
    //         case "pie":
    //             if (state.singleY) {
    //                 plot = {
    //                     type: state.type,
    //                     x: state.x,
    //                     y: state.singleY,
    //                     // bubble: state.sizeChannel || undefined,
    //                     // color: state.colorChannel || undefined
    //                 }
    //             }
    //             break;
    //
    //
    //
    //     }
    // }

    onChange(fn: (newPlot: PlotDefinition, oldPlot?: PlotDefinition) => any): PlotSelector {
        this.listeners.push(fn);
        return this;
    }

    dispose() {
        this.measuresUI.dispose();
        this.listeners = [];
        this.el.innerHTML = "";
    }
}

class PlotSelectorState {
    constructor(
        public type: string,
        public title: string | null = null,
        public x: DimensionAxis,
        public singleY: DimensionAxis | null = null,
        public multiY: MeasureAxis | null = null,
        public colorChannel: DimensionAxis | null = null,
        public sizeChannel: DimensionAxis | null = null,
        public forceZero: boolean = true
    ) {}

    public static empty(defaultX: string) {
        return new PlotSelectorState("bar", null, { field: defaultX })
    }

    static fromPlotDef(plotDef: PlotDefinition, defaultX: string): PlotSelectorState {
        const plot = plotDef.plot;
        const state = PlotSelectorState.empty(defaultX);
        if (!plot) {
            return state;
        }
        state.type = plot.type;
        state.title = plotDef.title ?? null;
        state.forceZero = plotDef.forceZero ?? true;
        state.x = plot.x;
        switch (plot.type) {
            case "bar":
            case "line":
            case "area":
                state.multiY = plot.y;
                break;

            // @ts-ignore
            case "xy":
                state.colorChannel = plot.color ?? null;
                state.sizeChannel = plot.bubble ?? null;
            case "boxplot":
            case "pie":
                state.singleY = plot.y;
        }
        return state;
    }
}