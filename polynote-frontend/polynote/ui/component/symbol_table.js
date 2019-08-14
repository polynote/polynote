import {UIEventTarget} from "../util/ui_event";
import {div, h3, span, table} from "../util/tags";
import {valueInspector} from "./value_inspector";

export class KernelSymbolsUI extends UIEventTarget {
    constructor(path) {
        super();
        this.symbols = {};
        this.presentedCell = 0;
        this.visibleCells = [];
        this.predefs = {};
        this.path = path;
        this.el = div(['kernel-symbols'], [
            h3([], ['Symbols']),
            this.tableEl = table(['kernel-symbols-table'], {
                header: ['Name', 'Type'],
                classes: ['name', 'type'],
                rowHeading: true,
                addToTop: true
            })
        ]);
        this.resultSymbols = this.tableEl.tBodies[0].addClass('results');
        this.scopeSymbols = this.tableEl.addBody().addClass('scope-symbols');
    }

    updateRow(tr, resultValue) {
        tr.resultValue = resultValue;
        tr.updateValues({type: span([], resultValue.typeName).attr('title', resultValue.typeName)})
    }

    addRow(resultValue, whichBody) {
        const tr = this.tableEl.addRow({
            name: resultValue.name,
            type: span([], [resultValue.typeName]).attr('title', resultValue.typeName)
        }, whichBody);
        tr.onclick = (evt) => {
            valueInspector.setEventParent(this);
            valueInspector.inspect(tr.resultValue, this.path);
        };
        tr.data = {name: resultValue.name, type: resultValue.typeName};
        tr.resultValue = resultValue;
        return tr;
    }

    addScopeRow(resultValue) {
        return this.addRow(resultValue, this.scopeSymbols);
    }

    addResultRow(resultValue) {
        return this.addRow(resultValue, this.resultSymbols);
    }

    addSymbol(resultValue) {
        const cellId = resultValue.sourceCell;
        const name = resultValue.name;

        if (!this.symbols[cellId]) {
            this.symbols[cellId] = {};
        }
        const cellSymbols = this.symbols[cellId];
        cellSymbols[name] = resultValue;
        if (cellId < 0) {
            this.predefs[cellId] = cellId;
        }

        if (cellId === this.presentedCell) {
            const existing = this.tableEl.findRows({name}, this.resultSymbols)[0];
            if (existing) {
                this.updateRow(existing, resultValue);
            } else {
                this.addResultRow(resultValue);
            }
        } else if (this.visibleCells.indexOf(cellId) >= 0 || this.predefs[cellId]) {
            const existing = this.tableEl.findRows({name}, this.scopeSymbols)[0];
            if (existing) {
                this.updateRow(existing, resultValue);
            } else {
                this.addScopeRow(resultValue);
            }
        }
    }

    presentFor(id, visibleCellIds) {
        visibleCellIds = [...Object.values(this.predefs), ...visibleCellIds];
        this.presentedCell = id;
        this.visibleCells = visibleCellIds;
        const visibleSymbols = {};
        visibleCellIds.forEach(id => {
            const cellSymbols = this.symbols[id];
            for (const name in cellSymbols) {
                if (cellSymbols.hasOwnProperty(name)) {
                    visibleSymbols[name] = cellSymbols[name];
                }
            }
        });

        // update all existing symbols, remove any that aren't visible
        [...this.scopeSymbols.rows].forEach(row => {
            if (row.data) {
                const sym = visibleSymbols[row.data.name];
                if (sym === undefined) {
                    row.parentNode.removeChild(row);
                } else {
                    if (sym.typeName !== row.data.type) {
                        this.updateRow(row, sym);
                    }
                    delete visibleSymbols[sym.name]
                }
            }
        });

        // append all the remaining symbols
        for (const name in visibleSymbols) {
            if (visibleSymbols.hasOwnProperty(name)) {
                this.addScopeRow(visibleSymbols[name]);
            }
        }

        // clear the result rows
        this.resultSymbols.innerHTML = "";

        // add all results for the current cell
        if (this.symbols[id]) {
            const cellSymbols = this.symbols[id];
            for (const name in cellSymbols) {
                if (cellSymbols.hasOwnProperty(name)) {
                    this.addResultRow(cellSymbols[name]);
                }
            }
        }
    }

    removeSymbol(name) {
        const existing = this.tableEl.findRowsBy(row => row.name === name);
        if (existing.length) {
            existing.forEach(tr => tr.parentNode.removeChild(tr));
        }
    }
}