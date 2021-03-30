"use strict";

import {FullScreenModal} from "../layout/modal";
import {div, TagElement} from "../tags";
import {ResultValue} from "../../data/result";
import match from "../../util/match";
import {
    DataRepr,
    LazyDataRepr,
    MIMERepr,
    StreamingDataRepr,
    StringRepr,
} from "../../data/value_repr";
import {contentTypeName, displayContent, displayData, displaySchema} from "../display/display_content";
import {DataReader} from "../../data/codec";
import {StructType} from "../../data/data_type";
import {TabNav} from "../layout/tab_nav";
import {NotebookStateHandler} from "../../state/notebook_state";

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

    inspect(nbState: NotebookStateHandler, resultValue: ResultValue, whichTab?: string): Promise<void> {
        this.content.innerHTML = "";
        let tabsPromise = Promise.resolve({} as Record<string, TagElement<any>>);

        resultValue.reprs.forEach(
            repr => tabsPromise = tabsPromise.then(tabs => {
                match(repr)
                    .when(StringRepr, str => tabs['String'] = div(['plaintext'], [document.createTextNode(str)]))
                    .when(MIMERepr, (mimeType, content) => {
                        const tabName = contentTypeName(mimeType);
                        whichTab = whichTab || tabName
                        return displayContent(mimeType, content).then((el: TagElement<"div">) => tabs[tabName] = el)
                    })
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
                                //tabs['Plot data'] = new PlotEditor(dispatcher, nbState, repr as StructStreamingDataRepr, resultValue.name, resultValue.sourceCell).container;
                            }
                            //tabs['View data'] = TableView.create(dispatcher, nbState, repr).el;
                        } catch(err) {
                            console.error(err);
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
                    const el = Object.values(tabs).shift();
                    el.dispatchEvent(new CustomEvent('TabDisplayed'))
                    el.dispatchEvent(new CustomEvent('becameVisible'))
                }
            }
        })
    }



}