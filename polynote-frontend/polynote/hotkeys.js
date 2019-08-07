export class KeyAction {
    /**
     * An action to be taken runAfter a KeyPress
     *
     * @param fun                   A function of type (Position, Range, Selection, Cell) -> Boolean. The return type indicates whether to stop propa
     * @param desc                  A human-readable description for this action (like "Run All Cells")
     * @param preventDefault        Whether to call preventDefault on the event
     * @param ignoreWhenSuggesting  Whether to ignore this action if the suggestion widget is currently open
     */
    constructor(fun, desc, preventDefault = false, ignoreWhenSuggesting = true) {
        this.fun = fun;
        this.desc = desc;
        this.preventDefault = preventDefault;
        this.ignoreWhenSuggesting = ignoreWhenSuggesting;
    }

    withPreventDefault(pd) {
        this.preventDefault = pd;
        return this;
    }

    withIgnoreWhenSuggesting(i) {
        this.ignoreWhenSuggesting = i;
        return this;
    }

    withDesc(desc) {
        this.desc = desc;
        return this;
    }

    // Create a new KeyAction that executes `otherAction` and then this action.
    // The new action takes the most restrictive configuration of `preventDefault` and `ignoreWhenSuggesting`
    runAfter(firstAction) {
        const fun = (position, range, selection, cell) => {
            firstAction.fun(position, range, selection, cell); // position, range, selection, cell might be mutated by this function!
            this.fun(position, range, selection, cell);
        };
        // preventDefault defaults to false, so if either action has explicitly set it to true we want it to take precedence.
        const preventDefault = this.preventDefault || firstAction.preventDefault;

        // ignoreWhenSuggesting defaults to true, so if either action has explicitly set it to false we want it to take precedence.
        const ignoreWhenSuggesting = this.ignoreWhenSuggesting && firstAction.ignoreWhenSuggesting;

        // `firstAction.name` takes precedence if defined
        const desc = firstAction.desc || this.desc;

        return new KeyAction(fun, desc, preventDefault, ignoreWhenSuggesting)
    }
}