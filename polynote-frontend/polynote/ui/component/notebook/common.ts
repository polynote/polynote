import {GoToDefinitionResponse} from "../../../data/messages";
import {append, NoUpdate, setProperty, setValue, updateProperty} from "../../../state";
import {languages, Uri} from "monaco-editor";
import {ServerStateHandler} from "../../../state/server_state";
import Definition = languages.Definition;
import {NotebookStateHandler} from "../../../state/notebook_state";
import {Either, Left, Right} from "../../../data/codec_types";
import {arrExists, posToRange} from "../../../util/helpers";


export function goToDefinition(
    notebookState: NotebookStateHandler,
    cellOrFile: Left<string> | Right<number>,
    offset: number
): Promise<Definition> {

    return new Promise<GoToDefinitionResponse>((resolve, reject) => {
        notebookState.state.requestedDefinition?.reject("cancelled");
        return notebookState.updateField("requestedDefinition", () => setValue({cellOrFile: cellOrFile, offset, resolve, reject}));
    }).then(result => {
        // TODO: if the location is a notebook cell?
        if (result.source && result.location && result.location[0]) {
            const loc = result.location[0];
            const uri = Uri.parse(loc.uri);
            const depSrc = {
                language: uri.scheme,
                content: result.source!,
                position: { lineNumber: loc.line, column: loc.column },
                sourceNotebook: notebookState
            }
            ServerStateHandler.get.updateAsync(state => {
                const dependencyLoaded = loc.uri in state.dependencySources;
                const fileIsOpen = arrExists(state.openFiles, of => of.path === loc.uri);
                return {
                    dependencySources: updateProperty(
                        loc.uri,
                        dependencyLoaded ? setProperty("position", depSrc.position)
                                         : setValue(depSrc)),
                    openFiles: fileIsOpen ? NoUpdate
                                          : append({ type: "dependency_source", path: loc.uri })
                };
            }).then(() => {
                ServerStateHandler.selectFile(loc.uri)
            })

        }
        return result.location.map(loc => ({
            uri: Uri.parse(loc.uri),
            range: posToRange(loc)
        }))
    })
}