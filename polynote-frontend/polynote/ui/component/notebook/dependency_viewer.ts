import * as monaco from "monaco-editor";
import {
    editor,
    IKeyboardEvent,
    IPosition,
    IRange,
    languages,
    MarkerSeverity,
    Range,
    SelectionDirection
} from "monaco-editor";
import {div, TagElement} from "../../tags";
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;
import {CodeCellModel} from "./cell";
import {findDefinitionLocation, IOpenInput, openDefinition} from "./common";
import {NotebookStateHandler} from "../../../state/notebook_state";
import {Either} from "../../../data/codec_types";
import {ServerStateHandler} from "../../../state/server_state";
import {Disposable} from "../../../state";

export class DependencyViewer extends Disposable {
    readonly el: TagElement<'div'>;
    readonly editorEl: TagElement<'div'>;
    readonly editor: IStandaloneCodeEditor;
    constructor(readonly uri: string, readonly content: string, readonly language: string, initialPos: IPosition, sourceNotebook: NotebookStateHandler) {
        super();

        const depId = new URL(uri).searchParams.get("dependency")!

        let lastLineNumber = initialPos.lineNumber;
        this.editorEl = div([], []);
        this.editor = monaco.editor.create(this.editorEl, {
            value: content,
            language: language,
            readOnly: true,
            fontFamily: 'Hasklig, Fira Code, Menlo, Monaco, fixed',
            fontSize: 15,
            fontLigatures: true,
            lineNumbers: "on",
            automaticLayout: true,
        });

        (this.editor as any)._codeEditorService.openCodeEditor = (input: IOpenInput, source: any, sideBySide: any) => {
            return openDefinition(sourceNotebook, language, {
                uri: input.resource,
                range: input.options?.selection || new Range(1, 0, 1, 0)
            })
        };

        this.editor.setPosition(initialPos);
        this.editor.revealLineNearTop(initialPos.lineNumber);

        (this.editor.getModel() as CodeCellModel).goToDefinition =
            (offset: number) => findDefinitionLocation(sourceNotebook, Either.left(depId), offset);

        this.el = div(['dependency-viewer', language], [this.editorEl]);
        this.disposeWith(sourceNotebook).onDispose.then(() => {
            ServerStateHandler.closeFile(uri, false);
        })

        ServerStateHandler.get.observeKey("dependencySources", (value, update) => {
            if (update.removedValues && uri in update.removedValues) {

            }
        }).disposeWith(this);

        ServerStateHandler.get.view("dependencySources").observeKey(uri, (value, update) => {
           if (!value) {
               this.tryDispose();
           } else {
               lastLineNumber = value.position.lineNumber;
               this.editor.setPosition(value.position);
               this.editor.revealLineNearTop(value.position.lineNumber);
           }
        });
    }
}