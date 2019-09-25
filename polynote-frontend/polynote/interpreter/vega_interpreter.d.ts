import {ClientInterpreter} from "./client_interpreter";
import {Result as VegaResult } from "vega-embed";
import {Output} from "../data/result";

export const VegaInterpreter: ClientInterpreter;

export const VegaClientResult: {
    plotToOutput(plot: VegaResult): Promise<Output>
};