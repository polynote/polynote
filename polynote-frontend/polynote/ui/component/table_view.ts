"use strict";

import {DataStream, LazyDataRepr, StreamingDataRepr, UpdatingDataRepr} from "../../data/value_repr";
import {div, iconButton, span, table, TableElement, tag, TagElement} from "../util/tags";
import {StructType, ArrayType, StructField, DataType} from "../../data/data_type";
import {SocketSession} from "../../comms";

function renderData(dataType: DataType, data: any): HTMLElement {
    // TODO: nicer display
    let value = '';
    if (dataType instanceof ArrayType || dataType instanceof StructType) {
        value = JSON.stringify(data);
    } else if (data !== null) {
        value = data.toString();
    } else {
        value = "<null>";
    }
    return span([], value).attr('title', value);
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

    constructor(readonly repr: StreamingDataRepr, readonly path: string) {
        const dataType = repr.dataType;
        this.fields = dataType.fields;
        const fieldClasses = dataType.fields.map(field => field.name);
        const fieldNames = dataType.fields.map(field => `${field.name}: ${field.dataType.typeName()}`);

        if (!SocketSession.get.isOpen) {
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
                this.prevButton = iconButton([], 'Previous page', '', '<< Prev').disable().click(evt => this.pagePrev()),
                this.nextButton = iconButton([], 'Next page', '', 'Next >>').click(evt => this.pageNext())
            ])
        ]);

        this.table.tBodies.item(0)!.appendChild(
            tag('tr', ['initial-msg'], {}, [
                tag('td', [],  {'colSpan': this.fields.length + ''},[
                    'Click "next page" (', span(['fas', 'icon'], ''), ') to load data.', tag('br'),
                    'This will force evaluation of lazy data.'
                ])])
        );

        this.stream = new DataStream(path, repr).batch(20);
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
            const row = this.fields.map(field => renderData(field.dataType, this.rows[i][field.name]));
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