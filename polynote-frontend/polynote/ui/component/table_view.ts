"use strict";

import {DataStream, LazyDataRepr, StreamingDataRepr, UpdatingDataRepr} from "../../data/value_repr";
import {div, iconButton, span, table, TableElement, tag, TagElement} from "../util/tags";
import {StructType, ArrayType, StructField, DataType} from "../../data/data_type";
import {SocketSession} from "../../comms";
import {NotebookUI} from "./notebook";
import {displayData} from "./display_content";

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

    constructor(readonly repr: StreamingDataRepr, readonly notebook: NotebookUI) {
        const dataType = repr.dataType;
        this.fields = dataType.fields || [new StructField("entries", dataType)]; // if dataType is not a StructType, create a dummy entry for it.
        const fieldClasses = this.fields.map(field => field.name);
        const fieldNames = this.fields.map(field => `${field.name}: ${field.dataType.typeName()}`);

        if (!notebook.socket.isOpen) {
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
                this.prevButton = iconButton([], 'Previous page', 'step-backward', '<< Prev').disable().click(evt => this.pagePrev()),
                this.nextButton = iconButton([], 'Next page', 'step-forward', 'Next >>').click(evt => this.pageNext())
            ])
        ]);

        this.table.tBodies.item(0)!.appendChild(
            tag('tr', ['initial-msg'], {}, [
                tag('td', [],  {'colSpan': this.fields.length + ''},[
                    'Click "next page" (', span(['fas', 'icon'], 'step-forward'), ') to load data.', tag('br'),
                    'This will force evaluation of lazy data.'
                ])])
        );

        this.stream = new DataStream(notebook.socket, repr).batch(20);
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