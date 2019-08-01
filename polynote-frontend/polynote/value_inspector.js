"use strict";

import {Modal} from "./modal";
import {div, button} from "./tags";
import {ResultValue} from "./result";
import {MIMERepr, DataRepr, LazyDataRepr, StreamingDataRepr, StringRepr} from "./value_repr";
import match from "./match";
import {displayContent, displayData, contentTypeName} from "./display_content"
import {ArrayType, StructType} from "./data_type";
import {PlotEditor} from "./plot_editor";
import {TableView} from "./table_view";
import {TabNav} from "./tab_nav";
import {DataReader} from "./codec";

function schemaToObject(structType) {
    const obj = {};
    for (let field of structType.fields) {
        const [name, desc] = dataTypeToObjectField(field.name, field.dataType);
        obj[name] = desc;
    }
    return obj;
}

function dataTypeToObjectField(name, dataType) {
    if (dataType instanceof StructType) {
        return [name, schemaToObject(dataType)];
    } else if (dataType instanceof ArrayType) {
        return [`${name}[]`, dataTypeToObjectField('element', dataType.element)[1]];
    } else {
        return [name, dataType.constructor.name(dataType)];
    }
}

class ValueInspector extends Modal {

    constructor() {
        super(
            div([], []),
            { windowClasses: ['value-inspector'] }
        );

        this.addEventListener('InsertCellAfter', evt => this.hide());
    }

    inspect(resultValue, notebookPath) {
        if (!(resultValue instanceof ResultValue)) {
            return;
        }

        this.content.innerHTML = "";
        let tabsPromise = Promise.resolve({});

        resultValue.reprs.forEach(
            repr => tabsPromise = tabsPromise.then(tabs => {
                match(repr)
                    .when(StringRepr, str => tabs['String'] = div(['plaintext'], [document.createTextNode(str)]))
                    .when(MIMERepr, (mimeType, content) => displayContent(mimeType, content).then(el => tabs[contentTypeName(mimeType)] = el))
                    .when(DataRepr, (dataType, data) => {
                        tabs[`Data(${resultValue.typeName})`] = displayData(dataType.decodeBuffer(new DataReader(data)))
                    })
                    .when(StreamingDataRepr, (handle, dataType, knownSize) => {
                        const repr = new StreamingDataRepr(handle, dataType, knownSize);
                        if (dataType instanceof StructType) {
                            tabs['Schema'] = div(['schema-display'], [displayData(schemaToObject(dataType), false, true)]);
                            tabs['Plot data'] = new PlotEditor(repr, notebookPath, resultValue.name, resultValue.sourceCell).setEventParent(this).container;
                            tabs['View data'] = new TableView(repr, notebookPath).el;
                        }
                    });
                return tabs;
            })
        );

        return tabsPromise.then(tabs => {
            this.content.appendChild(new TabNav(tabs).container);
            this.setTitle(`Inspect: ${resultValue.name}`);
            this.show();
        })
    }

}

export const valueInspector = new ValueInspector();