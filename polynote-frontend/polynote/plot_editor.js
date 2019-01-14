'use strict';

import { div, button, iconButton } from './tags.js'

class PlotEditor extends EventTarget {

    constructor(schema) {
        super();
        this.schema = schema;

        this.el = div(['plot-editor'], [

        ]);
    }



}