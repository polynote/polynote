"use strict";

import { VegaInterpreter } from "./vega_interpreter";
import {ClientResult} from "../data/result";

interface ClientInterpreter {
    languageTitle: string;
    highlightLanguage: string;
    interpret(code: string, cellContext: Record<string, any>): ClientResult[]
}

export const clientInterpreters: Record<string, ClientInterpreter> = {
    "vega": VegaInterpreter
};