import {div, h2, h3, img, para, polynoteLogo, span, tag, TagElement} from "../tags";
import {Disposable} from "../../state";
import {RecentNotebooks, RecentNotebooksHandler} from "../../state/preferences";
import {ServerStateHandler} from "../../state/server_state";

export class Home extends Disposable {
    readonly el: TagElement<"div">;

    constructor() {
        super()
        const recentNotebooks = tag('ul', ['recent-notebooks'], {}, []);
        this.el = div(['welcome-page'], [
            polynoteLogo(),
            h2([], ["Home"]),
            para([], [
                "To get started, open a notebook by clicking on it in the Notebooks panel, or create a new notebook by\n" +
                "             clicking the Create Notebook (",
                span(['create-notebook', 'icon'], [img(["icon"], "static/style/icons/fa/plus-circle.svg")]), ") button."
            ]),
            h3([], ["Recent Notebooks"]),
            recentNotebooks
        ]);

        const handleRecents = (recents: Readonly<RecentNotebooks>) => {
            recentNotebooks.innerHTML = "";
            recents.forEach(recent => {
                if (recent) {
                    const {name, path} = recent;
                    recentNotebooks.appendChild(tag('li', ['notebook-link'], {}, [
                        span([], [path]).click(() => ServerStateHandler.loadNotebook(path, true).then(() => {
                            ServerStateHandler.selectFile(path)
                        }))
                    ]))
                } else {
                    console.warn("There is a null or undefined in recent notebooks")
                }
            })
        }
        handleRecents(RecentNotebooksHandler.state)
        RecentNotebooksHandler.addObserver(nbs => handleRecents(nbs)).disposeWith(this)
    }
}