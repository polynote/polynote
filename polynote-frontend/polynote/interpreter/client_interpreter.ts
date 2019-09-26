"use strict";

import { VegaInterpreter } from "./vega_interpreter";
import {Result} from "../data/result";

export interface CellContext {
    id: number,
    availableValues: Record<string, any>
}

export interface ClientInterpreter {
    languageTitle: string;
    highlightLanguage: string;
    interpret(code: string, cellContext: CellContext): Result[]
}

export const clientInterpreters: Record<string, ClientInterpreter> = {
    "vega": VegaInterpreter
};