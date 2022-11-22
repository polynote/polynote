import {div, h2, TagElement} from "../tags";
import {LeftMenuSections, Pane} from "../layout/splitview";
import {
    LeftBarPreferences,
    LeftBarPrefsHandler,
    StickyLeftBarPreferences, ViewPreferences,
    ViewPrefsHandler
} from "../../state/preferences";
import {Disposable, setProperty, setValue, StateHandler} from "../../state";
import {deepCopy} from "../../util/helpers";

export type LeftPaneDrawer = { content: Pane, nav: LeftPaneNav, section?: LeftMenuSections };
export type LeftPaneModal = { nav: LeftPaneNav, action: () => void };

export type LeftPaneNav = { title: string, icon: TagElement<"button"> };

/**
 * Handler for the contents of the left pane. Handles opening/closing different panes, opening modals, and updating state accordingly.
 * Takes in an array of drawers and modals, where each element's name is also its class name in the left-hand bar, for easy state management.
 */
export class LeftPaneHandler extends Disposable {
    readonly leftBar: TagElement<"div">;
    readonly leftPane: TagElement<"div">;

    private leftBarPrefs: StateHandler<LeftBarPreferences["stickyLeftBar"]>;
    private leftPanePrefs: StateHandler<ViewPreferences["leftPane"]>;

    constructor(drawers: LeftPaneDrawer[], modals: LeftPaneModal[]) {
        super();

        this.leftBarPrefs = LeftBarPrefsHandler.lens("stickyLeftBar").disposeWith(this);
        this.leftPanePrefs = ViewPrefsHandler.lens("leftPane").disposeWith(this);

        this.leftBar = div(['sticky-left-bar'], []);
        // default to using the notebooks list if no state exists yet
        this.leftPane = div(['ui-panel'], [
            drawers[0].content.header,
            div(['ui-panel-content', 'left'], [drawers[0].content.el])
        ]);

        drawers.forEach((drawer) => {
            this.leftBar.appendChild(this.generateLeftBarEl(drawer).click(() => this.onDrawerChange(drawer.nav.title.toLowerCase())));
        })
        modals.forEach((modal) => {
            this.leftBar.appendChild(this.generateLeftBarEl(modal).click(() => modal.action()));
        })

        const leftBarStatus = (leftBarPrefs: LeftBarPreferences) => {
            const oldActiveEl = this.leftBar.querySelector('.active');
            oldActiveEl?.classList.remove('active');

            drawers.forEach((drawer) => {
                const title = drawer.nav.title.toLowerCase();
                if (leftBarPrefs.stickyLeftBar[title as keyof StickyLeftBarPreferences]) {
                    // The drawer's title (in lowercase) is its class - we use that to denote it as active in the left bar
                    const newActiveEl = this.leftBar.querySelector(`.${title}`);
                    newActiveEl?.classList.add('active');
                    this.setLeftPane(drawer.content.header, drawer.content.el);
                }
            })
        }
        leftBarStatus(LeftBarPrefsHandler.state);
        LeftBarPrefsHandler.addObserver(leftBarStatus).disposeWith(this);
    }

    // Handler for generating the initial element for drawers/modals in the left bar
    private generateLeftBarEl(el: LeftPaneDrawer | LeftPaneModal) {
        return div([el.nav.title.toLowerCase()], [
            h2([], [el.nav.title]),
            el.nav.icon
        ]);
    }

    // Handler for updating the left pane when a new drawer elemenet is selected
    private setLeftPane(header: TagElement<"h2">, el: TagElement<"div">): void {
        this.leftPane.innerHTML = ""; // we can't use replaceWith and have to modify the innerHTML because of the CSS grid
        this.leftPane.appendChild(header);
        this.leftPane.appendChild(div(['ui-panel-content', 'left'], [el]));
    }

    // Handler for changing the open drawer
    private onDrawerChange(selected: string) {
        let paneIsOpen = false;
        const newLeftBarPrefs: StickyLeftBarPreferences = this.leftBarPrefs.state;

        // For each preference, check if it should be open or closed now, closing the old one if necessary.
        for (const [key, val] of Object.entries(newLeftBarPrefs)) {
            const res = key === selected ? !val : false;
            if (res) paneIsOpen = true;
            newLeftBarPrefs[key as keyof StickyLeftBarPreferences] = res;
        }

        this.leftBarPrefs.updateAsync(() => setValue(deepCopy(newLeftBarPrefs)));

        // If there are no more open panes, then signal to collapse the left pane entirely
        this.leftPanePrefs.updateAsync(state => setProperty("collapsed", !paneIsOpen)).then(() => {
            window.dispatchEvent(new CustomEvent('resize'));
        })
    }
}