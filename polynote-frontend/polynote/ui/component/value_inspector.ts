"use strict";

import {FullScreenModal, Modal} from "./modal";
import {div, button, TagElement} from "../util/tags";
import {ResultValue} from "../../data/result";
import {MIMERepr, DataRepr, LazyDataRepr, StreamingDataRepr, StringRepr} from "../../data/value_repr";
import match from "../../util/match";
import {displayContent, displayData, contentTypeName, displaySchema} from "./display_content"
import {ArrayType, DataType, MapType, StructType} from "../../data/data_type";
import {PlotEditor} from "./plot_editor";
import {TableView} from "./table_view";
import {TabNav} from "./tab_nav";
import {DataReader} from "../../data/codec";


export class ValueInspector extends FullScreenModal {
    private static inst: ValueInspector;
    static get() {
        if (!ValueInspector.inst) {
            ValueInspector.inst = new ValueInspector();
        }
        return ValueInspector.inst;
    }
    constructor() {
        super(
            div([], []),
            { windowClasses: ['value-inspector'] }
        );
    }

    inspect(resultValue: ResultValue, notebookPath: string, jumpTo?: string) {
        this.content.innerHTML = "";
        let tabsPromise = Promise.resolve({} as Record<string, TagElement<any>>);

        resultValue.reprs.forEach(
            repr => tabsPromise = tabsPromise.then(tabs => {
                match(repr)
                    .when(StringRepr, str => tabs['String'] = div(['plaintext'], [document.createTextNode(str)]))
                    .when(MIMERepr, (mimeType, content) => displayContent(mimeType, content).then((el: TagElement<"div">) => tabs[contentTypeName(mimeType)] = el))
                    .when(DataRepr, (dataType, data) => {
                        tabs[`Data(${resultValue.typeName})`] = displayData(dataType.decodeBuffer(new DataReader(data)))
                    })
                    .when(LazyDataRepr, (handle, dataType, knownSize) => {
                        const tabName = `Data(${resultValue.typeName})`;
                        if (!tabs[tabName]) {
                            // TODO: a UI for downloading & viewing the data anyway.
                            tabs[tabName] = div(['lazy-data'], [
                                `The data was too large to transmit (${knownSize ? knownSize + " bytes" : 'unknown size'}), so it can't be displayed here.`
                            ]);
                        }
                    })
                    .when(StreamingDataRepr, (handle, dataType, knownSize) => {
                        const repr = new StreamingDataRepr(handle, dataType, knownSize);
                        if (dataType instanceof StructType) {
                            tabs['Schema'] = displaySchema(dataType);
                            try {
                                tabs['Plot data'] = new PlotEditor(repr, notebookPath, resultValue.name, resultValue.sourceCell, () => this.hide()).container;
                                tabs['View data'] = new TableView(repr, notebookPath).el;
                            } catch(err) {
                                console.log(err);
                            }
                        }
                    });
                return tabs;
            })
        );

        return tabsPromise.then(tabs => {
            if (Object.keys(tabs).length) {
                const nav = new TabNav(tabs);
                if (jumpTo && tabs[jumpTo]) {
                    nav.showItem(jumpTo);
                }
                this.content.appendChild(nav.el);
                this.setTitle(`Inspect: ${resultValue.name}`);
                this.show();
            }
        })
    }

}