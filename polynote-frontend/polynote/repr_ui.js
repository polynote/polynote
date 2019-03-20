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

export class ReprUI extends UIEventTarget {

    constructor(name, path, reprs, targetEl) {
        super();
        this.name = name;
        this.path = path;
        this.reprs = reprs;
        this.targetEl = targetEl;
        this.contentContainer = div(['repr-ui-content'], []);
        this.tabUI = new TabUI({ content: this.contentContainer });
        this.el = div(['repr-ui'], [this.tabUI.el, this.contentContainer]);
    }

    show() {
        this.targetEl.parentNode.replaceChild(this.el, this.targetEl);
        this.tabUI.addTab('Default', span([],'Default'), {content: this.targetEl});
        this.reprs.forEach(repr => this.addReprTabs(repr));
        // kind of hacky way to hide tab if there's only one
        if (Object.keys(this.tabUI.tabs).length <= 1) {
           this.tabUI.tabContainer.style.display = "none";
        }
    }

    addReprTabs(repr) {
        if (repr instanceof StreamingDataRepr && repr.dataType instanceof StructType) {
            // various table views
            this.tableView = new TableView(repr, this.path);
            this.tabUI.addTab('Table', span([], 'Data'), {content: this.tableView.el});
            this.plotView = new PlotEditor(repr, this.path, this.name);
            this.tabUI.addTab('Plot', span([], 'Plot'), {content: this.plotView.el});
        }
    }



}

function renderData(dataType, data) {
    // TODO: nicer display
    let value = '';
    if (dataType instanceof ArrayType || dataType instanceof StructType) {
        value = JSON.stringify(item);
    } else {
        value = data.toString();
    }
    return span([], value).attr('title', value);
}

class TableView {

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
            tag('tr', ['.initial-msg'], {'colspan': fields.length + ''}, ['Click "next page" (', span(['fas', 'icon'], ''), ') to load data.'])
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