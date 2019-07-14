"use strict";

import {UIEventTarget, UIEvent} from "./ui_event";
import {DataStream, StreamingDataRepr} from "./value_repr";
import {HandleData} from "./messages";
import {DataReader} from "./codec";
import {TabUI} from "./ui";
import {div, iconButton, span, table, tag} from "./tags";
import {StructType, ArrayType, BinaryType} from "./data_type";
import {SocketSession} from "./comms";
import {PlotEditor} from "./plot_editor";

export class ReprDataRequest extends UIEvent {
    constructor(reprType, handleId, count, onComplete, onFail) {
        super("ReprDataRequest", {handleType: reprType.handleTypeId, handleId, count, onComplete, onFail});
    }
}


function renderData(dataType, data) {
    // TODO: nicer display
    let value = '';
    if (dataType instanceof ArrayType || dataType instanceof StructType) {
        value = JSON.stringify(data);
    } else {
        value = data.toString();
    }
    return span([], value).attr('title', value);
}

export class TableView {

    constructor(repr, path) {
        if (!(repr instanceof StreamingDataRepr) || !(repr.dataType instanceof StructType))
            throw "TableView can only be created for streaming struct data";
        this.repr = repr;
        this.path = path;
        const dataType = repr.dataType;
        const fields = this.fields = dataType.fields;
        const fieldNames = this.fieldNames = dataType.fields.map(field => field.name);
        this.el = div(['table-view'], [
            this.table = table([], {
                header: fieldNames,
                classes: fieldNames,
                rows: []
            }),
            this.paginator = div(['paginator'], [
                this.prevButton = iconButton([], 'Previous page', '', '<< Prev').disable().click(evt => this.pagePrev()),
                this.nextButton = iconButton([], 'Next page', '', 'Next >>').click(evt => this.pageNext())
            ])
        ]);

        this.table.tBodies.item(0).appendChild(
            tag('tr', ['initial-msg'], {}, [
                tag('td', [],  {'colspan': fields.length + ''},[
                    'Click "next page" (', span(['fas', 'icon'], ''), ') to load data.', tag('br'),
                    'This will force evaluation of lazy data.'
                ])])
        );

        this.stream = new DataStream(path, repr, SocketSession.current).batch(20);
        this.rows = [];
        this.currentPos = 0;
    }

    addBatch(batch) {
        const start = this.rows.length;
        this.rows.push(...batch);
        const end = this.rows.length;
        this.displayItems(start, end);
    }

    displayItems(start, end) {
        start = Math.max(start, 0);
        this.table.tBodies.item(0).innerHTML = '';
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
            this.stream.requestNext().then(batch => this.addBatch(batch)).then(_ => this.prevButton.enabled = true);
        } else {
            this.nextButton.enabled = false;
        }
    }

    pagePrev() {
        if (this.currentPos > 0) {
            this.displayItems(this.currentPos - 20, this.currentPos);
            this.nextButton.enabled = true;
        }

        if (this.currentPos === 0) {
            this.prevButton.enabled = false;
        }
    }

}