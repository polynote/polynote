import {GoToDefinitionResponse} from "../../../data/messages";
import {append, NoUpdate, setProperty, setValue, updateProperty} from "../../../state";
import {languages, Uri, Range, IRange} from "monaco-editor";
import {ServerStateHandler} from "../../../state/server_state";
import Definition = languages.Definition;
import Location = languages.Location;
import {NotebookStateHandler} from "../../../state/notebook_state";
import {Either, Left, Right} from "../../../data/codec_types";
import {arrExists, nameFromPath, posToRange} from "../../../util/helpers";
import {languageOfExtension} from "../../../interpreter/file_extensions";


export function findDefinitionLocation(
    notebookState: NotebookStateHandler,
    cellOrFile: Left<string> | Right<number>,
    offset: number
): Promise<Definition> {

    return new Promise<GoToDefinitionResponse>((resolve, reject) => {
        notebookState.state.requestedDefinition?.reject("cancelled");
        return notebookState.updateField("requestedDefinition", () => setValue({cellOrFile: cellOrFile, offset, resolve, reject}));
    }).then(result => {

        return result.location.map(loc => {
            const absolute = loc.uri.startsWith("#") ? loc.uri : new URL(loc.uri, document.location.href).href;
            return {
                uri: Uri.parse(absolute),
                range: posToRange(loc)
            }
        })
    })
}

export function openDefinition(notebookState: NotebookStateHandler, lang: string, location: Location): Promise<void> {
    const uriString = location.uri.toString(true);  // true means "don't render this URI incorrect by mangling its characters"

    // even a relative "#X" URI gets turned into a file:///#X URI by the Microsoft thing. So really just have to
    // assume that any fragment is a cell link
    if (location.uri.fragment) {
        const cellId = cellIdFromHash(location.uri.fragment);
        notebookState.selectCellAt(cellId, Range.getStartPosition(location.range));
        return Promise.resolve();
    }

    const params = new URLSearchParams(location.uri.query);
    const filename = params.get("dependency");
    const filePieces = filename?.split('.')
    const fileExtension = filePieces?.[filePieces.length - 1]
    const fileLanguage = languageOfExtension(fileExtension) || lang;

    if (uriString in ServerStateHandler.state.dependencySources) {
        return ServerStateHandler.get.updateAsync(state => {
            const fileIsOpen = arrExists(state.openFiles, of => of.path === uriString);
            return {
                dependencySources: updateProperty(uriString, {position: Range.getStartPosition(location.range)}),
                openFiles: fileIsOpen ? NoUpdate : append({type: "dependency_source", path: uriString})
            };
        }).then(() => ServerStateHandler.selectFile(uriString))
    } else {
        return fetch(uriString, {
            method: "GET",
            mode: "same-origin",
        }).then(response => response.text()).then(source => {
            ServerStateHandler.get.updateAsync(state => ({
                dependencySources: updateProperty(
                    uriString,
                    setValue({
                        language: fileLanguage,
                        content: source,
                        position: Range.getStartPosition(location.range),
                        sourceNotebook: notebookState
                    })
                ),
                openFiles: append({ type: "dependency_source", path: uriString })
            }))
        }).then(() => ServerStateHandler.selectFile(uriString))
    }
}

export interface IOpenInput {
    options?: {
        selection?: IRange
    },
    resource: Uri
}

export function cellIdFromHash(hash: string): number {
    return parseInt(hash.slice("Cell".length))
}