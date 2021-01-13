import {button, checkbox, div, dropdown, h3, h4, iconButton, label, span, TagElement, textbox} from "../tags";
import {
    BoolType,
    ByteType,
    DataType, DateType,
    DoubleType, IntType, LongType, NumericTypes,
    OptionalType,
    ShortType,
    StringType, StructField,
    StructType, TimestampType, UnsafeLongType
} from "../../data/data_type";
import {
    LayerSpec,
    TopLevelSpec
} from "vega-lite/build/src/spec";
import {Encoding} from "vega-lite/build/src/encoding";
import {Mark, AnyMark} from "vega-lite/build/src/mark";
import match from "../../util/match";
import {GroupAgg, Histogram, Select, TableOp} from "../../data/messages";
import {Pair} from "../../data/codec";
import {deepCopy, deepEquals, diffArray, isDescendant} from "../../util/helpers";
import {Disposable, Observer, StateHandler} from "../../state/state_handler";

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
    colorChannel?: string
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
    color?: DimensionAxis
}

export interface PiePlot {
    type: "pie"
    x: DimensionAxis
    y: DimensionAxis
}

export interface HistogramPlot {
    type: "histogram"
    x: DimensionAxis
    binCount: number
}

export interface Facet {
    row?: string
    col?: string
    width?: number
    height?: number
}

export type Plot = BarPlot | LinePlot | StackedAreaPlot | XYScatter | BoxPlot | PiePlot | HistogramPlot
export interface PlotDefinition {
    value: string
    title?: string
    plot?: Plot
    forceZero?: boolean
    facet?: Facet
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

function plotToSpec(plotDef: PlotDefinition, schema: StructType): TopLevelSpec {
    if (!plotDef.plot)
        throw new Error("Plot definition is incomplete!");

    const spec = (() => {
        const plot: Plot = plotDef.plot;
        switch (plot.type) {
            case "bar":       return barPlot(plotDef, plot, schema);
            case "line":      return linePlot(plotDef, plot, schema);
            case "area":      return stackedAreaPlot(plotDef, plot, schema);
            case "xy":        return xyScatterPlot(plotDef, plot, schema);
            case "boxplot":   return boxPlot(plotDef, plot, schema);
            case "pie":       return piePlot(plotDef, plot, schema);
            case "histogram": return histogramPlot(plotDef, plot, schema);
        }
    })();

    if (plotDef.plot.type !== 'histogram' && plotDef.facet && (plotDef.facet.row || plotDef.facet.col)) {
        const facet: any = {};
        const facetFieldTypes: Record<string, DataType> = {};
        deepDimensionFields(schema.fields).forEach(field => facetFieldTypes[field.name] = field.dataType);
        if (plotDef.facet.row && facetFieldTypes[plotDef.facet.row]) {
            facet.row = { field: plotDef.facet.row, type: dimensionType(facetFieldTypes[plotDef.facet.row]) };
        }
        if (plotDef.facet.col && facetFieldTypes[plotDef.facet.col]) {
            facet.column = { field: plotDef.facet.col, type: dimensionType(facetFieldTypes[plotDef.facet.col]) };
        }

        const facetSpec: any = {
            $schema: spec.$schema,
            data: spec.data,
            facet: facet,
            width: plotDef.facet.width,
            height: plotDef.facet.height,
            title: plotDef.title,
            spec: {
                ...spec,
                width: plotDef.facet.width,
                height: plotDef.facet.height,
                title: undefined,
                $schema: undefined,
                data: undefined
            } as any
        };

        return facetSpec;
    }

    return spec;
}

function tableOps(plotDef: PlotDefinition): TableOp[] {
    const plot = plotDef.plot;
    if (!plot)
        throw new Error("Plot definition is incomplete!");

    const facetFields: string[] = [];
    if (plotDef.facet) {
        if (plotDef.facet.row) facetFields.push(plotDef.facet.row);
        if (plotDef.facet.col) facetFields.push(plotDef.facet.col);
    }

    switch (plot.type) {
        case "bar":
        case "line":
        case "area":
            const colorField = plot.y.colorChannel ? [plot.y.colorChannel] : [];
            return [
                new GroupAgg([plot.x.field, ...colorField, ...facetFields], plot.y.series.flatMap(series => series.aggregation ? [new Pair(series.field, series.aggregation)] : [])),
            ];

        case "xy":
            const selectFields = [plot.x.field, plot.y.field, ...facetFields];
            if (plot.bubble)
                selectFields.push(plot.bubble.field);
            if (plot.color)
                selectFields.push(plot.color.field);
            return [new Select(selectFields)];

        case "boxplot":
            const boxColorField = plot.color ? [plot.color.field] : [];
            return [
                new GroupAgg([plot.x.field, ...boxColorField, ...facetFields], [new Pair(plot.y.field, "quartiles")]),
            ]

        case "pie":
            return [new GroupAgg([plot.x.field, ...facetFields], [new Pair(plot.y.field, "sum")])];

        case "histogram":
            return [new Histogram(plot.x.field, plot.binCount)]
    }
}

export function plotToVega(plotDef: PlotDefinition, schema: StructType): TopLevelSpec {
    const spec = plotToSpec(plotDef, schema) as any;
    spec.width = 'container';
    return spec as TopLevelSpec;
}

export function plotToVegaCode(plotDef: PlotDefinition, schema: StructType): string {
    const spec = plotToVega(plotDef, schema) as any;
    spec.data.values = '$DATA_STREAM$';
    const ops = tableOps(plotDef);
    let streamSpec = `${plotDef.value}.useUnsafeLongs()`;
    ops.forEach(op => streamSpec = op.streamCode(streamSpec));
    return JSON.stringify(spec).replace('"$DATA_STREAM$"', streamSpec);
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
        dataType === UnsafeLongType ||
        dataType === StringType ||
        dataType === DateType ||
        dataType === TimestampType
    )
}

type TopLevelUnitSpec = TopLevelSpec & {
    mark: AnyMark
    encoding: Encoding<any>
}

function basicPlot(plotDef: PlotDefinition, plot: BasicPlot, schema: StructType, xType?: 'nominal' | 'ordinal' | 'quantitative'): TopLevelUnitSpec {
    const xAxisType = xType || dimensionType(schema.fieldType(plot.x.field)!);

    const result: TopLevelUnitSpec = {
        $schema: 'https://vega.github.io/schema/vega-lite/v4.json',
        title: plotDef.title,
        data: {name: plotDef.value},
        mark: {
            type: plot.type,
            tooltip: { content: 'data' }
        },
        encoding: {
            x: {
                field: plot.x.field,
                type: xAxisType,
                axis: {
                    title: plot.x.title
                }
            },
            y: {
                field: '',
                type: 'quantitative',
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
            ...result.encoding.y,
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
            ...result.encoding.y,
            field: seriesName(s),
            type: 'quantitative'
        };
        if (plot.y.colorChannel) {
            result.encoding.color = {
                field: plot.y.colorChannel,
                type: dimensionType(schema.fieldType(plot.y.colorChannel)!)
            }
        }
    }

    return result;
}

function quartilesTransform(series: PlotSeries[], colorChannel?: string) {
    return colorChannel ? undefined : [{
        fold: series.map(seriesName),
    }, {
        // this transform handles fields which aren't quartiles
        calculate: "isNumber(datum.value) ? { q1: datum.value, median: datum.value, q3: datum.value } : datum.value",
        as: "value"
    }];
}

function barPlot(plotDef: PlotDefinition, plot: BarPlot, schema: StructType): TopLevelSpec {
    const base = basicPlot(plotDef, plot, schema);
    const xEncoding = base.encoding.x;

    // handle non-stacked case - change X encoding
    if (!plot.stacked && (plot.y.series.length > 1 || plot.y.colorChannel)) {
        base.encoding.x = {
            field: (plot.y.series.length > 1) ? "key" : plot.y.colorChannel!,
            type: (plot.y.series.length > 1) ? 'nominal' : dimensionType(schema.fieldType(plot.y.colorChannel!)!),
            axis: { title: '' }
        }
    }

    // Handle quartiles – we'll use median for the bar.
    const confidenceSeries = plot.y.series.filter(series => series.aggregation === 'quartiles');
    if (confidenceSeries.length > 0) {
        const yAxis = { title: plot.y.title || "value" }
        const transform = quartilesTransform(plot.y.series, plot.y.colorChannel);
        if (transform) {
            base.transform = transform;
        }
        (base.encoding.y as any).field += ".median";
    }


    // handle non-stacked case - add faceting
    if (!plot.stacked && (plot.y.series.length > 1 || plot.y.colorChannel)) {
        return {
            $schema: base.$schema,
            data: base.data,
            facet: {
                column: xEncoding as any
            },
            spec: {
                ...base,
                $schema: undefined,
                data: undefined
            }
        } as any as TopLevelSpec;
    }

    return base;
}

function linePlot(plotDef: PlotDefinition, plot: LinePlot, schema: StructType): TopLevelSpec {
    const result = basicPlot(plotDef, plot, schema);
    if (plot.point) {
        result.mark = {
            type: "line",
            point: true,
            tooltip: {
                content: 'data'
            }
        }
    }

    const confidenceSeries = plot.y.series.filter(series => series.aggregation === 'quartiles');
    if (confidenceSeries.length > 0) {
        const yAxis = { title: plot.y.title || "value" }
        const resultCopy = {...result};
        delete resultCopy.mark;
        if (resultCopy.encoding)
            delete resultCopy.encoding.y;

        const yField = plot.y.colorChannel ? seriesName(plot.y.series[0]) : 'value';
        const colorField = plot.y.colorChannel || 'key';
        const colorType = plot.y.colorChannel ? dimensionType(schema.fieldType(plot.y.colorChannel)!) : 'nominal';

        const layerResult: LayerSpec = {
            ...resultCopy,
            transform: quartilesTransform(plot.y.series, plot.y.colorChannel),
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
                            field: `${yField}.q1`,
                            type: 'quantitative',
                            axis: yAxis,
                            scale: { zero: !!plotDef.forceZero }
                        },
                        y2: {
                            field: `${yField}.q3`
                        },
                        opacity: {value: 0.3},
                        color: {
                            field: colorField,
                            type: colorType
                        }
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
                            field: `${yField}.median`,
                            type: 'quantitative',
                            axis: yAxis
                        },
                        color: {
                            field: colorField,
                            type: colorType
                        }
                    },
                }],
            encoding: undefined
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
        mark: { type: "point", tooltip: {content: 'data'} },
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
    const size = 14;
    const base: TopLevelSpec = {
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
            },
            // TODO: disabling color series because it doesn't work well.
            // color: mapOpt(
            //     plot.color,
            //     colorAxis => ({
            //         field: colorAxis.field,
            //         type: dimensionType(schema.fieldType(colorAxis.field)!),
            //         title: colorAxis.title
            //     })
            // )
        },
        layer: [
            {
                mark: { type: "rule", style: "boxplot-rule" },
                encoding: {
                    y: { field: `${baseY}.min`, type: "quantitative", title: plot.y.title || plot.y.field, scale: { zero: plotDef.forceZero }},
                    y2: { field: `${baseY}.max` }
                }
            },
            {
                mark: { type: "bar", style: "boxplot-box", size, tooltip: { content: 'data' } },
                encoding: {
                    y: { field: `${baseY}.q1`, type: "quantitative" },
                    y2: { field: `${baseY}.q3` },
                }
            },
            {
                mark: { type: "tick", color: "white", style: 'boxplot-median', size },
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

    // TODO: encode color doesn't really work. Make it work.

    return base;
}

function piePlot(plotDef: PlotDefinition, plot: PiePlot, schema: StructType): TopLevelSpec {
    const schemaField = schema.fields.find(field => field.name === plot.x.field);
    if (!schemaField) {
        throw new Error(`Field ${plot.x.field} is not in the schema`);
    }
    return {
        $schema: 'https://vega.github.io/schema/vega-lite/v4.json',
        title: plotDef.title,
        data: {name: plotDef.value},
        mark: {
            type: 'arc',
            tooltip: { content: 'data' }
        },
        encoding: {
            color: {
                field: plot.x.field,
                type: 'nominal',
                legend: {
                    title: plot.x.title
                }
            },
            theta: {
                field: `sum(${plot.y.field})`,
                type: 'quantitative'
            }
        }
    };
}

function histogramPlot(plotDef: PlotDefinition, plot: HistogramPlot, schema: StructType): TopLevelSpec {
    const schemaField = schema.fields.find(field => field.name === plot.x.field);
    if (!schemaField) {
        throw new Error(`Field ${plot.x.field} is not in the schema`);
    }

    return {
        $schema: 'https://vega.github.io/schema/vega-lite/v4.json',
        title: plotDef.title,
        data: {name: plotDef.value},
        mark: {
            type: "bar",
            tooltip: { content: 'data' }
        },
        encoding: {
            x: {
                field: "start",
                type: "quantitative",
                bin: "binned",
                axis: {
                    title: plot.x.title,
                    tickCount: plot.binCount
                }
            },
            x2: { field: "end" },
            y: {
                field: "count",
                type: "quantitative",
                axis: {
                    title: "Count"
                }
            }
        }
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

function deepFields(fields: StructField[], predicate: (type: DataType) => boolean, path?: string): StructField[] {
    const currentPath = path ? `${path}.` : "";
    return fields.flatMap(field =>
        match(field.dataType)
            .typed<StructField[]>()
            // TODO: does backend support field traversal like this? Both spark and collections?
            .when(StructType, fields => deepFields(fields, predicate, currentPath + field.name))
            .when(OptionalType, type => deepFields([new StructField(field.name, type)], predicate, currentPath))
            .whenP(predicate, typ => [new StructField(`${currentPath}${field.name}`, typ)])
            .otherwise([])
    );
}

function deepNumericFields(fields: StructField[]): StructField[] {
    return deepFields(fields, type => NumericTypes.indexOf(type) >= 0);
}

function deepDimensionFields(fields: StructField[]): StructField[] {
    return deepFields(fields, isDimension);
}

function deepAllFields(fields: StructField[]): StructField[] {
    return deepFields(fields, _ => true)
}

const Measures = {
    mean: "Mean",
    sum: "Sum",
    count: "Count",
    quartiles: "Median & error"
}

class MeasurePicker {
    readonly el: HTMLDivElement;
    private readonly popup: HTMLDivElement;

    private listeners: ((name: string, measure: string, self: MeasurePicker) => void)[] = [];

    constructor(readonly field: StructField) {
        this.el = div(['measure-picker', 'item'], [
            div(['field'], [
                span(['name'], field.name),
                span(['indicator'], '▸')
            ]),
            this.popup = div(['measures-popup', 'dropdown', 'open'], [
                ...Object.entries(Measures).map(
                    pair => button([pair[0]], {name: pair[0]}, pair[1]).click(evt => this.trigger(pair[0]))
                )
            ])
        ]).listener('mouseenter', () => this.recomputeVisible());
    }

    dispose() {
        this.listeners = [];
        this.el.innerHTML = "";
    }

    private trigger(measure: string) {
        this.listeners.forEach(fn => fn(this.field.name, measure, this));
    }

    onSelect(fn: (name: string, measure: string, self: MeasurePicker) => void): MeasurePicker {
        this.listeners.push(fn);
        return this;
    }

    recomputeVisible() {
        let last = this.popup.lastElementChild;
        while (last && (last instanceof HTMLElement ? !last.offsetParent : true)) {
            last.classList.remove('last-visible');
            last = last.previousElementSibling;
        }
        if (last) {
            last.classList.add('last-visible');
        }
    }

    hideMeasure(measure: string) {
        const button = (this.popup.children.namedItem(measure)) as HTMLButtonElement;
        if (button) {
            button.style.display = 'none';
            button.disabled = true;
            this.recomputeVisible();
        }
    }

    showMeasure(measure: string) {
        const button = (this.popup.children.namedItem(measure)) as HTMLButtonElement;
        if (button) {
            button.style.display = '';
            button.disabled = false;
            this.recomputeVisible();
        }
    }
}

class MeasuresUI extends Disposable {
    readonly el: HTMLDivElement;
    private selectedMeasuresEl: TagElement<"div">;
    private availableMeasuresEl: TagElement<"div">;
    private pickers: Record<string, MeasurePicker>;
    private listeners: ((selected: PlotSeries[], added?: PlotSeries, removed?: PlotSeries) => void)[] = [];
    private addButton: TagElement<'button'>;
    private title: TagElement<'span'>;

    private _disabled: boolean = false;
    private _selectedMeasures: PlotSeries[] = [];
    get selectedMeasures(): PlotSeries[] { return deepCopy(this._selectedMeasures); }

    constructor(private availableFields: StructField[], initialSeries?: PlotSeries[], private mode: 'single' | 'multiple' = 'multiple') {
        super();
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
            div(['selected-measures'], [
                h4([], [
                    this.title = span([], 'Measures'),
                    this.addButton = iconButton(['add'], 'Add measure', 'plus-circle', 'Add')
                        .click(_ => this.showMeasures())
                ]),
                this.selectedMeasuresEl = div([], []),

            ]),
            this.availableMeasuresEl = div(['available-measures'], [
                h4([], "Available fields"),
                div(['measure-pickers', 'dropdown', 'open'], Object.values(this.pickers).map(picker => picker.el))
            ])
        ]);

        this.availableMeasuresEl.style.display = 'none';

        if (initialSeries) {
            initialSeries.forEach(series => this.addMeasure(series.field, series.aggregation || "mean", this.pickers[series.field]))
        }

        this.setMode(mode);

        this.onDispose.then(() => {
            this.listeners = [];
            for (let picker of Object.keys(this.pickers)) {
                this.pickers[picker].dispose();
            }
            this.el.innerHTML = "";
        })
    }

    setMode(mode: 'single' | 'multiple') {
        this.mode = mode;
        this.el.classList.remove('single', 'multiple');
        this.el.classList.add(mode);
        if (mode === 'single' && this._selectedMeasures.length > 0) {
            this.addButton.disabled = true;
        }
        this.title.innerHTML = mode === 'single' ? 'Measure' : 'Measures';
    }

    private showMeasures() {
        const hideMeasures = (evt: any) => {
            if (!evt.key || evt.key === 'Escape') {
                if (evt.target instanceof HTMLElement && isDescendant(evt.target, this.el)) {
                    return;
                }
                window.removeEventListener('mousedown', hideMeasures);
                window.removeEventListener('keydown', hideMeasures);
                this.availableMeasuresEl.style.display = 'none';
            }
        }
        window.addEventListener('keydown', hideMeasures);
        window.addEventListener('mousedown', hideMeasures);
        this.availableMeasuresEl.style.display = 'block';
    }

    private removeMeasure(field: string, aggregation: string) {
        const index = this._selectedMeasures.findIndex(series => series.field === field && series.aggregation === aggregation);
        if (index >= 0) {
            const [removed] = this._selectedMeasures.splice(index, 1);
            this.selectedMeasuresEl.removeChild(this.selectedMeasuresEl.children[index]);
            this.pickers[field].showMeasure(aggregation);
            const measures = this.selectedMeasures;
            if (this.mode !== 'single' || this._selectedMeasures.length === 0) {
                this.addButton.disabled = false;
            }
            this.listeners.forEach(
                fn => fn(measures, undefined, removed)
            );
        }
    }

    private addMeasure(field: string, aggregation: string, picker: MeasurePicker) {
        const added = {field, aggregation}
        this._selectedMeasures.push(added);
        this.selectedMeasuresEl.appendChild(
            div(['selected-measure'], [
                span([], `${aggregation}(${field})`),
                iconButton(['remove', 'red'], 'Remove', 'minus-circle-red', "Remove")
                    .click(_ => this.removeMeasure(field, aggregation))
            ])
        );
        picker.hideMeasure(aggregation);
        const measures = this.selectedMeasures;
        this.availableMeasuresEl.style.display = 'none';
        if (this.mode === 'single' && this._selectedMeasures.length > 0) {
            this.addButton.disabled = true;
        }
        this.listeners.forEach(
            fn => fn(measures, added)
        );
    }

    get disabled(): boolean {
        return this._disabled;
    }

    set disabled(disabled: boolean) {
        if (disabled !== this._disabled) {
            this._disabled = disabled;
            if (disabled)
                this.el.classList.add('disabled');
            else
                this.el.classList.remove('disabled');
            this.el.querySelectorAll("button").forEach(el => {
                if (el.style.display !== 'none')
                    el.disabled = disabled
            });
            this.addButton.disabled = this.mode === 'single' && this._selectedMeasures.length > 0
        }
    }

    onChange(fn: (selected: PlotSeries[], added?: PlotSeries, removed?: PlotSeries) => void): MeasuresUI {
        this.listeners.push(fn);
        return this;
    }

    bind(handler: StateHandler<PlotSeries[] | undefined>): MeasuresUI {
        this.onChange(selected => {
            if (!deepEquals(selected, handler.state))
                handler.update(s => selected);
        });
        handler.addObserver(series => {
            const [added, removed] = series ? diffArray(series, this.selectedMeasures) : [[], this.selectedMeasures];
            const listeners = this.listeners;
            this.listeners = [];
            for (let s of added) {
                if (this.pickers[s.field] && s.aggregation)
                    this.addMeasure(s.field, s.aggregation, this.pickers[s.field]);
            }
            for (let s of removed) {
                if (s.aggregation)
                    this.removeMeasure(s.field, s.aggregation);
            }
            this.listeners = listeners;
        }, this);
        return this;
    }
}

export class PlotSelector {
    readonly el: TagElement<'div'>;
    private readonly stateHandler: StateHandler<PlotSelectorState>;
    private listeners: ((newPlot: PlotDefinition, oldPlot?: PlotDefinition) => any)[] = [];
    private publishObserver: [Observer<PlotSelectorState>, string];

    private dimensionFields: StructField[];
    private measuresUI: MeasuresUI;
    private xField: TagElement<'select'>;
    private xDimension: TagElement<'select'>;
    private yField: TagElement<'select'>;
    private colorChannel: TagElement<'select'>;
    private facetCheckbox: TagElement<'label'>;
    private _disposer: Disposable = new Disposable();
    private _disabled: boolean = false;

    constructor(private name: string, schema: StructType, initialState?: PlotDefinition) {
        const dimensionFields = this.dimensionFields = deepDimensionFields(schema.fields);

        if (dimensionFields.length === 0) {
            throw new Error("No possible dimension fields");
        }

        const dimensionOptions = Object.fromEntries(dimensionFields.map(field => [field.name, field.name]));
        const measureFields    = deepNumericFields(schema.fields);
        const measureOptions   = Object.fromEntries(measureFields.map(field => [field.name, field.name]));

        const state = initialState ? PlotSelectorState.fromPlotDef(initialState, dimensionFields[0].name) : PlotSelectorState.empty(name, dimensionFields[0].name, name);
        const stateHandler = this.stateHandler = StateHandler.from(state, this._disposer);

        const typeHandler         = stateHandler.lensOpt("type");
        const facetHandler        = stateHandler.lensOpt("facet");
        const xHandler            = stateHandler.lens("x");
        const colorSeriesHandler  = stateHandler.lens("seriesColorChannel");
        const singleSeriesHandler = stateHandler.lens("singleY");
        const multiSeriesHandler  = stateHandler.lens("multiY");

        typeHandler.addObserver(this.onSetType, this._disposer);
        singleSeriesHandler.addObserver(field => this.measuresUI.setMode(field ? 'single' : 'multiple'), this._disposer);

        this.publishObserver = stateHandler.addObserver(
            (newState, oldState) => this.listeners.forEach(l => l(newState.toPlotDef(), oldState.toPlotDef())),
            this._disposer
        );
        stateHandler.addObserver(newState => {
            if (newState.facet) {
                this.el.classList.add('facet');
                this.facetCheckbox.querySelector('input')!.checked = true;
            } else {
                this.el.classList.remove('facet');
                this.facetCheckbox.querySelector('input')!.checked = false;
            }
        }, this._disposer);

        this.el = div(['plot-selector', state.type, ...(state.facet ? ['facet'] : [])], [
            div(['top-tools'], [
                h3(['table-name'], 'Plot'),
                div(['type-selector'], [
                    label([], "Type",
                        dropdown(['plot-type'], plotTypes, state.type).bind(typeHandler))
                ]),
                div(['title-input'], [
                    label([], "Title",
                        textbox([], "Plot title", initialState?.title || initialState?.value || "")
                            .bindWithDefault(stateHandler.lens('title'), ""))
                ]),
                this.facetCheckbox = checkbox(['facet'], 'Facet', !!(state.facet)).onValueChange<boolean>(checked => {
                    if (checked) {
                        this.el.classList.add('facet');
                    } else {
                        this.el.classList.remove('facet');
                    }
                }),
                div(['facet-options'], [
                    label(['facet-row'], "Facet row",
                        dropdown([], { "": "None", ...dimensionOptions}, state.facet?.row || undefined)
                            .bindWithDefault(facetHandler.lens("row"), "")),
                    label(['facet-col'], "Facet column",
                        dropdown([], { "": "None", ...dimensionOptions}, state.facet?.col || undefined)
                            .bindWithDefault(facetHandler.lens("col"), "")),
                    label(['facet-width'], "Width",
                        textbox([], "Width", state.facet?.width?.toString() || undefined, "number")
                            .bindWithDefault(facetHandler.lens("width"), 50)
                            .attr("step", "1")),
                    label(['facet-width'], "Height",
                        textbox([], "Height", state.facet?.height?.toString() || undefined, "number")
                            .bindWithDefault(facetHandler.lens("height"), 50)
                            .attr("step", "1"))
                ])
            ]),
            div(['x-axis-config'], [
                h3([], "X Axis"),
                label(['title'], "Title",
                    textbox([], "Axis title", initialState?.plot?.x.title)
                        .bindWithDefault(xHandler.lens("title"), "")),
                label(['dimension-field'], "Dimension",
                    this.xDimension = dropdown([], dimensionOptions, initialState?.plot?.x.field)
                        .bind(xHandler.lens("field"))),
                label(['x-field'], "Field",
                    this.xField = dropdown([], measureOptions, initialState?.plot?.x.field)
                        .bind(xHandler.lens("field")))
            ]),
            div(['y-axis-config'], [
                h3([], "Y Axis"),
                label(['title'], "Title",
                    textbox([], "Axis title", state.yTitle || undefined)
                        .bindWithDefault(stateHandler.lens("yTitle"), "")),
                label(['series-field'], "Series",
                    this.colorChannel = dropdown([], { "": "Measures", ...dimensionOptions }, state.seriesColorChannel || undefined)
                        .bindWithDefault(colorSeriesHandler, "")),
                div(['measure-configs'], [
                    this.measuresUI = new MeasuresUI(measureFields, state.multiY || undefined, state.seriesColorChannel ? 'single' : 'multiple')
                        .bind(multiSeriesHandler)
                ]),
                label(['y-field'], "Field",
                    this.yField = dropdown([], measureOptions, state.singleY || undefined)
                        .bindPartial(singleSeriesHandler)),
                checkbox(['force-zero'], 'Force zero', state.forceZero)
                    .bind(stateHandler.lens("forceZero"))
            ]),
            div(['additional-config'], [
                div(['bar-options'], [
                    h4([], 'Options'),
                    checkbox(['stack-bar'], "Stacked", state.stackBar)
                        .bind(stateHandler.lens("stackBar"))
                ]),
                div(['xy-options'], [
                    h4([], 'Options'),
                    label(['point-color'], "Point color",
                        dropdown([], { "": "None", ...dimensionOptions}, state.pointColorChannel || undefined)
                            .bindWithDefault(stateHandler.lens("pointColorChannel"), "")),
                    label(['bubble-size'], "Bubble size",
                        dropdown([], {"": "None", ...measureOptions}, state.bubbleSizeChannel || undefined)
                            .bindWithDefault(stateHandler.lens("bubbleSizeChannel"), ""))
                ]),
                div(['histogram-options'], [
                    h4([],'Options'),
                    label(['bin-count'], "Bin count",
                        textbox([], undefined, state.binCount.toString(), "number")
                            .bind(stateHandler.lens("binCount"))
                            .attrs({step: "1", min: "2"})
                    )
                ])
            ])
        ]);
    }

    setPlot(plotDef: PlotDefinition): void {
        const state = PlotSelectorState.fromPlotDef(plotDef, plotDef.plot?.x?.field || this.dimensionFields[0].name)
        if (!deepEquals(state, this.stateHandler.state)) {
            this.stateHandler.removeObserver(this.publishObserver);
            this.stateHandler.update(_ => state);
            this.stateHandler.addObserver(this.publishObserver[0], this._disposer, this.publishObserver[1]);
        }
    }

    private get hasFacet() {
        const state = this.stateHandler.state;
        return state.facet && (state.facet.col || state.facet.row);
    }

    private onSetType: (typ: string) => void = (typ: string) => {
        this.el.className = 'plot-selector';
        this.el.classList.add(typ);
        if (this.hasFacet)
            this.el.classList.add('facet');

        // make sure state from newly-visible dropdowns gets noticed
        this.el.querySelectorAll('select').forEach(
            el => {
                if (!el.classList.contains('plot-type') && el.offsetParent !== null)
                    el.dispatchEvent(new CustomEvent('change'));
            }
        );

        // update some terminology
        if (typ === 'boxplot') {
            this.colorChannel.options[0].innerHTML = 'None';
        } else {
            this.colorChannel.options[0].innerHTML = 'Measures';
        }
    }

    get currentPlot(): PlotDefinition {
        return this.stateHandler.state.toPlotDef();
    }

    get disabled(): boolean {
        return this._disabled;
    }

    set disabled(disabled: boolean) {
        if (this._disabled !== disabled) {
            this._disabled = disabled;
            if (disabled) {
                this.el.classList.add('disabled');
            } else {
                this.el.classList.remove('disabled');
            }
            this.el.querySelectorAll<Element & {disabled: boolean}>("input, select, textarea").forEach(el => el.disabled = disabled);
            this.measuresUI.disabled = disabled;
        }
    }

    onChange(fn: (newPlot: PlotDefinition, oldPlot?: PlotDefinition) => any): PlotSelector {
        this.listeners.push(fn);
        return this;
    }

    dispose() {
        this.measuresUI.dispose();
        this._disposer.tryDispose();
        this.listeners = [];
        this.el.innerHTML = "";
    }
}

class PlotSelectorState {
    constructor(
        public name: string,
        public type: string,
        public title: string | undefined = undefined,
        public x: DimensionAxis,
        public singleY: string | undefined = undefined,
        public multiY: PlotSeries[] | undefined = undefined,
        public yTitle: string | undefined = undefined,
        public seriesColorChannel: string | undefined = undefined,
        public pointColorChannel: string | undefined = undefined,
        public bubbleSizeChannel: string | undefined = undefined,
        public stackBar: boolean = true,
        public point: boolean = false,
        public forceZero: boolean = true,
        public binCount: number = 10,
        public facet: Facet | undefined = undefined
    ) {}

    public static empty(name: string, defaultX: string, title?: string) {
        return new PlotSelectorState(name, "bar", title, { field: defaultX })
    }

    static fromPlotDef(plotDef: PlotDefinition, defaultX: string): PlotSelectorState {
        const plot = plotDef.plot;
        const state = PlotSelectorState.empty(plotDef.value, defaultX);
        if (!plot) {
            return state;
        }
        state.type = plot.type;
        state.title = plotDef.title;
        state.forceZero = plotDef.forceZero ?? true;
        state.x = plot.x;
        state.facet = plotDef.facet;

        switch (plot.type) {
            // @ts-ignore
            case "bar":
                state.stackBar = plot.stacked || false;
            case "line":
            case "area":
                state.multiY = plot.y.series;
                state.yTitle = plot.y.title;
                state.seriesColorChannel = plot.y.colorChannel;
                break;

            // @ts-ignore
            case "xy":
                state.pointColorChannel = plot.color?.field;
                state.bubbleSizeChannel = plot.bubble?.field;
            case "boxplot":
            case "pie":
                if (plot.type === 'boxplot') {
                    state.seriesColorChannel = plot.color?.field;
                }
                state.singleY = plot.y.field;
                state.yTitle = plot.y.title;
        }
        return state;
    }

    toPlotDef() {
        const plotDef: PlotDefinition = {
            value: this.name,
            title: this.title,
            forceZero: this.forceZero,
            facet: this.facet
        };

        let plot: Plot | undefined;

        switch (this.type) {
            case "bar":
            case "line":
            case "area":
                const multiY = this.multiY ?? [];
                plot = {
                    type: this.type,
                    x: this.x,
                    y: {
                        series: this.seriesColorChannel ? multiY.slice(0, 1) : multiY,
                        title: this.yTitle || undefined,
                        colorChannel: this.seriesColorChannel || undefined
                    },
                    point: this.point || undefined
                }

                if (plot && plot.type === 'bar') {
                    plot.stacked = this.stackBar || undefined;
                }
                break;
            case "xy":
            case "boxplot":
            case "pie":
                if (this.singleY) {
                    plot = {
                        type: this.type,
                        x: this.x,
                        y: {
                            field: this.singleY,
                            title: this.yTitle || undefined
                        },
                        bubble: this.bubbleSizeChannel && this.type === 'xy' ? { field: this.bubbleSizeChannel} : undefined,
                        color:
                            this.pointColorChannel && (this.type === 'xy') ? { field: this.pointColorChannel } :
                            this.seriesColorChannel && (this.type === 'boxplot') ? { field: this.seriesColorChannel } :
                                undefined
                    }
                }
                break;
            case "histogram":
                plot = {
                    type: "histogram",
                    x: this.x,
                    binCount: this.binCount
                }
                break;
        }
        plotDef.plot = plot;
        return plotDef;
    }
}