"use strict";

import {NotebookMessageDispatcher} from "../messaging/dispatcher";
import {NotebookStateHandler} from "../state/notebook_state";
import {ServerStateHandler} from "../state/server_state";
import {ArrayType, DataType, StructField, StructType} from "../../../data/data_type";
import {displayData} from "../display_content";
import {div, iconButton, span, table, TableElement, tag, TagElement} from "../../util/tags";
import {DataStream} from "../messaging/datastream";
import {StreamingDataRepr} from "../../../data/value_repr";

function renderData(fieldName: string | undefined, dataType: DataType, data: any): HTMLElement {
    // TODO: nicer display
    if (dataType instanceof ArrayType || dataType instanceof StructType) {
        return displayData(data, fieldName);
    } else if (data !== null && data !== undefined) {
        return span([], data.toString()).attr('title', data.toString())
    }
    return span([], "<null>")
}

export class TableView {
    private fields: StructField[];
    el: TagElement<"div">;
    private table: TableElement;
    private paginator: TagElement<"div">;
    private prevButton: TagElement<"button">;
    private nextButton: TagElement<"button">;
    private stream: DataStream;
    private rows: Record<string, any>[]; // TODO: anything better than `any` here?
    private currentPos: number;

    constructor(private dispatcher: NotebookMessageDispatcher, private nbState: NotebookStateHandler, readonly repr: StreamingDataRepr) {
        const dataType = repr.dataType;
        this.fields = dataType.fields || [new StructField("entries", dataType)]; // if dataType is not a StructType, create a dummy entry for it.
        const fieldClasses = this.fields.map(field => field.name);
        const fieldNames = this.fields.map(field => `${field.name}: ${field.dataType.typeName()}`);

        const connectionStatus = ServerStateHandler.get.getState().connectionStatus;
        if (connectionStatus === "disconnected") {
            this.el = div(['table-view', 'disconnected'], [
                "Not connected to server – must be connected in order to view data."
            ]);
            return;
        }

        this.el = div(['table-view'], [
            this.table = table([], {
                header: fieldNames,
                classes: fieldClasses,
                rows: []
            }),
            this.paginator = div(['paginator'], [
                this.prevButton = iconButton([], 'Previous page', 'step-backward', '<< Prev').disable().click(() => this.pagePrev()),
                this.nextButton = iconButton([], 'Next page', 'step-forward', 'Next >>').click(() => this.pageNext())
            ])
        ]);

        this.table.tBodies.item(0)!.appendChild(
            tag('tr', ['initial-msg'], {}, [
                tag('td', [],  {'colSpan': this.fields.length + ''},[
                    'Click "next page" (', span(['fas', 'icon'], 'step-forward'), ') to load data.', tag('br'),
                    'This will force evaluation of lazy data.'
                ])])
        );

        this.stream = new DataStream(this.dispatcher, this.nbState, repr).batch(20);
        this.rows = [];
        this.currentPos = 0;
    }

    // TODO: replace any with real type
    addBatch(batch: any) {
        const start = this.rows.length;
        this.rows.push(...batch);
        const end = this.rows.length;
        this.displayItems(start, end);
    }

    displayItems(start: number, end: number) {
        start = Math.max(start, 0);
        this.table.tBodies.item(0)!.innerHTML = '';
        for (let i = start; i < end && i < this.rows.length; i++) {
            const row = this.fields.map(field => renderData(field.name, field.dataType, this.rows[i].hasOwnProperty(field.name) ? this.rows[i][field.name] : this.rows[i]));
            this.table.addRow(row);
        }
        this.currentPos = start;
    }

    pageNext() {
        if (this.currentPos + 20 < this.rows.length) {
            this.displayItems(this.currentPos + 20, this.currentPos + 40);
        } else if (!this.stream.terminated) {
            this.stream.requestNext().then(batch => this.addBatch(batch)).then(_ => this.prevButton.disabled = false);
        } else {
            this.nextButton.disabled = true;
        }
    }

    pagePrev() {
        if (this.currentPos > 0) {
            this.displayItems(this.currentPos - 20, this.currentPos);
            this.nextButton.disabled = false;
        }

        if (this.currentPos === 0) {
            this.prevButton.disabled = true;
        }
    }

}