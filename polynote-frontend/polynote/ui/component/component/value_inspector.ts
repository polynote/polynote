"use strict";

import {FullScreenModal} from "./modal";
import {div, TagElement} from "../../util/tags";
import {ResultValue} from "../../../data/result";
import match from "../../../util/match";
import {DataRepr, LazyDataRepr, MIMERepr, StreamingDataRepr, StringRepr} from "../../../data/value_repr";
import {contentTypeName, displayContent, displayData, displaySchema} from "../display_content";
import {DataReader} from "../../../data/codec";
import {StructType} from "../../../data/data_type";
import {TabNav} from "./tab_nav";
import {PlotEditor} from "./plot_editor";
import {TableView} from "./table_view";
import {NotebookMessageDispatcher} from "../messaging/dispatcher";
import {NotebookStateHandler} from "../state/notebook_state";

export class ValueInspector extends FullScreenModal {
    private constructor() {
        super(
            div([], []),
            { windowClasses: ['value-inspector'] }
        );
    }
    private static inst: ValueInspector;
    static get get() {
        if (!ValueInspector.inst) {
            ValueInspector.inst = new ValueInspector();
        }
        return ValueInspector.inst;
    }

    inspect(dispatcher: NotebookMessageDispatcher, nbState: NotebookStateHandler, resultValue: ResultValue, whichTab?: string) {
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
                        try {
                            if (dataType instanceof StructType) {
                                tabs['Schema'] = displaySchema(dataType);
                                tabs['Plot data'] = new PlotEditor(dispatcher, nbState, repr, resultValue.name, resultValue.sourceCell).container;
                            }
                            tabs['View data'] = new TableView(dispatcher, nbState, repr).el;
                        } catch(err) {
                            console.log(err);
                        }
                    });
                return tabs;
            })
        );

        return tabsPromise.then(tabs => {
            if (Object.keys(tabs).length) {
                const nav = new TabNav(tabs);
                this.content.appendChild(nav.el);
                this.setTitle(`Inspect: ${resultValue.name}`);
                this.show();

                if (whichTab && tabs[whichTab]) {
                    nav.showItem(whichTab);
                } else {
                    Object.values(tabs).shift().dispatchEvent(new CustomEvent('TabDisplayed'))
                }
            }
        })
    }



}