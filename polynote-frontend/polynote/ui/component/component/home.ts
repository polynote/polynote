import {div, h2, h3, img, para, span, tag, TagElement} from "../../util/tags";
import {LoadNotebook, ServerMessageDispatcher} from "../messaging/dispatcher";
import {RecentNotebooks, RecentNotebooksHandler} from "../state/storage";

export class Home {
    readonly el: TagElement<"div">;

    constructor(dispatcher: ServerMessageDispatcher) {

        const recentNotebooks = tag('ul', ['recent-notebooks'], {}, []);
        this.el = div(['welcome-page'], [
            img([], "static/style/polynote.svg", "Polynote"),
            h2([], ["Home"]),
            para([], [
                "To get started, open a notebook by clicking on it in the Notebooks panel, or create a new notebook by\n" +
                "             clicking the Create Notebook (",
                span(['create-notebook', 'icon'], [img(["icon"], "static/style/icons/fa/plus-circle.svg")]), ") button."
            ]),
            h3([], ["Recent Notebooks"]),
            recentNotebooks
        ]);

        const handleRecents = (recents: RecentNotebooks) => {
            recentNotebooks.innerHTML = "";
            recents.forEach(({name, path}) => {
                recentNotebooks.appendChild(tag('li', ['notebook-link'], {}, [
                    span([], [path]).click(() => dispatcher.dispatch(new LoadNotebook(path)))
                ]))
            })
        }
        handleRecents(RecentNotebooksHandler.getState())
        RecentNotebooksHandler.addObserver(nbs => handleRecents(nbs))
    }
}