import {PlotDefinition, PlotSelector} from "./plot_selector";
import {DataType, StructType} from "../../data/data_type";
import {div, span, TagElement} from "../tags";
import {TabNav} from "../layout/tab_nav";
import {ClientResult, ResultValue} from "../../data/result";
import {DataRepr, MIMERepr, reprPriority, StreamingDataRepr, StringRepr} from "../../data/value_repr";
import {collectMatch, deepCopy, deepEquals, findInstance} from "../../util/helpers";
import match, {matchS} from "../../util/match";
import {parseContentType} from "../display/display_content";
import {TableView} from "../layout/table_view";
import {NotebookMessageDispatcher} from "../../messaging/dispatcher";
import {NotebookStateHandler} from "../../state/notebook_state";
import {Disposable} from "../../state";

export interface PlotViz {
    type: "plot",
    value: string,
    plotDefinition: PlotDefinition
}

export interface SchemaViz {
    type: "schema"
    value: string
}

export interface TableViz {
    type: "table"
    value: string
    rowRange: [number, number]
}

export interface DataViz {
    type: "data"
    value: string
}

export interface MIMEViz {
    type: "mime",
    value: string,
    mimeType: string
}

export interface StringViz {
    type: "string",
    value: string
}

export type Viz = PlotViz | SchemaViz | TableViz | DataViz | MIMEViz | StringViz
export type ViewType = 'plot' | 'schema' | 'table' | 'data' | 'mime' | 'string';
export function viewType(str?: string): ViewType | undefined {
    switch (str) {
        case 'plot': case 'schema': case 'table': case 'data': case 'mime': case 'string':
            return str;
        default:
            return undefined;
    }
}

export function isViz(obj?: any): obj is Viz {
    return viewType(obj?.type) !== undefined && typeof(obj?.value) === 'string';
}

export function parseViz(str: string): Viz | undefined {
    try {
        const viz = JSON.parse(str);
        if (viewType(viz?.type)) {
            return viz;
        }
    } catch (err) {
        return undefined;
    }
    return undefined;
}

export function parseMaybeViz(str: string): Viz | { value: string } | undefined {
    try {
        const viz = JSON.parse(str);
        if (viewType(viz?.type) || viz?.value) {
            return viz;
        }
    } catch (err) {
        return undefined;
    }
    return undefined;
}

export function saveViz(viz: Viz): string {
    return JSON.stringify(viz);
}

const PlotTitle = 'Plot';
const SchemaTitle = 'Schema';
const TableTitle = 'Browse';
const DataTitle = 'Data';
const StringTitle = 'Plain';

function viewTitle(viz: Viz): string {
    switch (viz.type) {
        case "plot": return PlotTitle;
        case "schema": return SchemaTitle;
        case "table": return TableTitle;
        case "data": return DataTitle;
        case "string": return StringTitle;
        case "mime":
            // TODO: friendlier titles for known mime types
            return viz.mimeType
    }
}

export class VizSelector extends Disposable {
    private listeners: ((newViz: Viz, oldViz: Viz) => any)[] = [];
    private plotSelector?: PlotSelector;
    private tableView?: TableView;
    private tabNav: TabNav;
    private _disabled: boolean = false;
    readonly el: TagElement<'div'>;

    constructor(private value: string, private resultValue: ResultValue, dispatcher: NotebookMessageDispatcher, state: NotebookStateHandler, private viz: Viz) {
        super();

        this.onDispose.then(() => {
            this.listeners = [];
            this.plotSelector?.dispose();
        });

        const opts: Record<string, TagElement<'div'>> = {};

        // TODO: this ordering is kind of wrong... should show "preferred" MIME reprs before anything else?
        const reprs = [...resultValue.reprs].sort(
            (a, b) => -(reprPriority(b) - reprPriority(a))
        );

        reprs.forEach(repr => match(repr)
            .whenInstance(StreamingDataRepr, streamRepr => {
                try {
                    this.tableView = TableView.create(dispatcher, state, streamRepr, true);
                    if (streamRepr.dataType instanceof StructType) {
                        this.plotSelector = new PlotSelector(value, streamRepr.dataType, this.viz.type === 'plot' ? this.viz.plotDefinition : undefined);
                        opts[PlotTitle] = this.plotSelector.el.listener(
                            'TabDisplayed',
                            () => this.update({ type: 'plot', value: value, plotDefinition: this.plotSelector!.currentPlot })
                        );
                    }
                } catch (e) {
                    console.log("Error while creating PlotSelector for", streamRepr, e)
                }

                opts[SchemaTitle] = div([], []).listener('TabDisplayed',
                    () => this.update({ type: 'schema', value: value })
                );

                opts[TableTitle] = this.tableView!.el.listener(
                    'TabDisplayed',
                    () => this.update({ type: 'table', value: value, rowRange: this.tableView!.range })
                );
            })
            .whenInstance(DataRepr, dataRepr => {
                opts[DataTitle] = div([], []).listener('TabDisplayed',
                    () => this.update({ type: 'data', value: value })
                )
            })
            .whenInstance(MIMERepr, mimeRepr => {
                const [mimeType, args] = parseContentType(mimeRepr.mimeType);
                opts[viewTitle({type: 'mime', value: value, mimeType: mimeRepr.mimeType})] =
                    div([], [])
                        .listener(
                            'TabDisplayed',
                            () => this.update( { type: 'mime', value: value, mimeType: mimeType })
                        );
            })
            .when(StringRepr, string =>
                opts[StringTitle] = div([], [])
                    .listener('TabDisplayed',  () => this.update({ type: 'string', value: value })))
            );

        this.tabNav = new TabNav(opts, 'horizontal');

        let initialView: string = viewTitle(this.viz);

        this.tabNav.showItem(initialView);

        this.el = div(['viz-selector'], [this.tabNav.el]);

        this.plotSelector?.onChange((newPlot) => this.update({ type: 'plot', value: value, plotDefinition: newPlot }));
        this.tableView?.onChange(() => this.update({ type: 'table', value: value, rowRange: this.tableView!.range }));

    }

    get currentViz(): Viz {
        return deepCopy(this.viz);
    }

    set currentViz(viz: Viz) {
        this.viz = viz;
        if (viz.type === 'plot' && this.plotSelector && viz.plotDefinition) {
            this.plotSelector.setPlot(viz.plotDefinition);
        }
        this.tabNav.showItem(viewTitle(viz));
    }

    private update(newViz: Viz) {
        const oldViz = this.viz;
        if (!deepEquals(newViz, oldViz)) {
            this.viz = deepCopy(newViz);
            this.listeners.forEach(listener => listener(newViz, oldViz));
        }
    }

    onChange(fn: (newViz: Viz, oldViz: Viz) => any): VizSelector {
        this.listeners.push(fn);
        return this;
    }

    private disabler = (evt: Event) => {
        evt.stopPropagation();
        evt.preventDefault();
    }

    set disabled(disabled: boolean) {
        this._disabled = disabled;
        this.tabNav.disabled = disabled;
        if (this.plotSelector)
            this.plotSelector.disabled = disabled;

        if (disabled) {
            this.el.classList.add('disabled');
            this.el.addEventListener("mousedown", this.disabler, true);
            this.el.addEventListener("click", this.disabler, true);
            this.el.addEventListener("focus", this.disabler, true);
        } else {
            this.el.classList.remove('disabled');
            this.el.removeEventListener("mousedown", this.disabler, true);
            this.el.removeEventListener("click", this.disabler, true);
            this.el.removeEventListener("focus", this.disabler, true);
        }

        this.tabNav.disabled =  disabled;
    }

    get disabled(): boolean {
        return this._disabled;
    }

    get tableHTML(): string {
        return this.tableView?.asHTML || "";
    }
}