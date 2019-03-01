"use strict";

import {UIEventTarget, UIEvent} from "./ui_event";
import {StreamingDataRepr} from "./value_repr";
import {HandleData} from "./messages";
import {DataReader} from "./codec";

export class ReprDataRequest extends UIEvent {
    constructor(reprType, handleId, count, onComplete, onFail) {
        super("ReprDataRequest", {handleType: reprType.handleTypeId, handleId, count, onComplete, onFail});
    }
}

export class ReprUI extends UIEventTarget {

    constructor(name, reprs) {
        super();
        this.name = name;
        this.reprs = reprs;
    }

    show() {
        // TODO: a real UI... don't ask for data unless the user does through the UI
        //       this is just a stub to get the data requests going
        this.reprs.forEach(repr => this.getReprData(repr).then(data => console.log(data)));
    }

    getReprData(repr) {
        if (repr instanceof StreamingDataRepr) {

            const decodeValues = (data) => {
              const values = [];
              for (let i = 0; i < data.length; i++) {
                  values[i] = repr.dataType.decodeBuffer(new DataReader(data[i]));
              }
              return values;
            };

            return new Promise((resolve, reject) => this.dispatchEvent(new ReprDataRequest(StreamingDataRepr, repr.handle, 50, resolve, reject)))
                .then(decodeValues);
        } else {
            return Promise.resolve(repr)
        }
    }

}