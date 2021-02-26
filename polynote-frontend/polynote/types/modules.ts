/**
 * Ok, unlike globals.ts, this file contains type definitions that are in the *module* namespace.
 */

export const __dummy = null; // Apparently, we need at least one import or export to make this into a Module. No, really.

/**
 * Not sure why @types for Tinycon doesn't have the reset function, so here it is.
 *
 * TODO: contrib back to @types/tinycon.
 */
declare module "tinycon" {
    export function reset(): void
}

import { editor } from "monaco-editor"; // note: this import is _required_ even though IntelliJ thinks it isn't
declare module "monaco-editor" {
    namespace editor {
        // @ts-ignore ignore use of private Monaco API
        import {IContextKeyService} from 'monaco-editor/esm/vs/platform/contextkey/common/contextkey.js'

        interface IStandaloneCodeEditor {
            _contextKeyService: IContextKeyService
        }
    }

}