'use strict';

import { div, button, iconButton } from './tags.js'

class PlotEditor extends EventTarget {

    constructor(repr, path) {
        super();
        this.repr = repr;
        this.fields = repr.dataType.fields;

        this.el = div(['plot-editor'], [
            div(['field-list'], )
        ]);
    }



}