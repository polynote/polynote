/**
 * Extensions to Monaco types live here.
 */

import {editor, IDisposable, IEvent} from "monaco-editor";
import IEditorContribution = editor.IEditorContribution;
import IContextKey = editor.IContextKey;

/**
 * For some reason the editor contrib types aren't published...
 */

// see: https://github.com/microsoft/vscode/blob/master/src/vs/editor/contrib/suggest/suggestController.ts
export interface SuggestController extends IEditorContribution {
    readonly _widget: { _value: { suggestWidgetVisible: IContextKey<boolean>}}
}

// see: https://github.com/microsoft/vscode/blob/master/src/vs/editor/contrib/folding/foldingModel.ts
export interface FoldingModel {
    onDidChange<T>(listener: (e: T) => any, thisArg?: any): IDisposable
}

// see: https://github.com/microsoft/vscode/blob/master/src/vs/editor/contrib/folding/folding.ts
export interface FoldingController extends IEditorContribution {
    getFoldingModel(): Promise<FoldingModel | null> | null;
}
