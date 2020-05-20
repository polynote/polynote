import {StateHandler} from "./state_handler";

test("setting the state updates observers", done => {
    const sh = new StateHandler(1);
    sh.addObserver((next, prev) => {
        expect(prev).toBe(1);
        expect(next).toBe(2);
        done()
    });
    sh.setState(2)
});

test("updating the state updates observers", done => {
    const sh = new StateHandler(1);
    sh.addObserver((next, prev) => {
        expect(prev).toBe(1);
        expect(next).toBe(2);
        done()
    });
    sh.updateState(() => 2)
});

test("setting the state without changing it does NOT update observers", () => {
    const mockObs = jest.fn((next: number, prev: number) => {});
    const sh = new StateHandler(1);
    sh.addObserver(mockObs);
    sh.setState(1);
    expect(mockObs).toHaveBeenCalledTimes(0);
    sh.setState(2);
    expect(mockObs).toHaveBeenCalledTimes(1);
});

test("views are updated by parent state changes", done => {
    const sh = new StateHandler({key: 1});
    const shV = sh.view("key");
    shV.addObserver((next: number, prev: number) => {
        expect(prev).toBe(1);
        expect(next).toBe(2);
        done()
    });
    sh.setState({key: 2});
    expect(shV.getState()).toBe(sh.getState().key)
});

test("views can update parent state", done => {
    const sh = new StateHandler({key: 1});
    const shV = sh.view("key");
    sh.addObserver((next: {key: number}, prev: {key: number}) => {
        expect(prev.key).toBe(1);
        expect(next.key).toBe(2);
        done()
    });
    shV.setState(2);
    expect(shV.getState()).toBe(sh.getState().key)
});

test("views can be passed a constructor", done => {
    const sh = new StateHandler({key: 1});
    class AddingStorageHandler extends StateHandler<number> {
        readonly someProperty: number = 100;
        constructor( initial: number) {
            super(initial);
        }

        updateState(f: (s: number) => number) {
            const add1 = (s: number) => f(s) + 1;
            super.updateState(add1);
        }
    }
    const shV = sh.view("key", AddingStorageHandler);
    expect(shV.someProperty).toBe(100);
    expect(shV instanceof AddingStorageHandler).toBeTruthy();
    sh.addObserver((next: {key: number}, prev: {key: number}) => {
        expect(prev.key).toBe(1);
        expect(next.key).toBe(3);
        done()
    });
    shV.updateState(() => 2);
    expect(shV.getState()).toBe(sh.getState().key)
});

test("xmapview can be used to view a modified value", done => {
    const sh = new StateHandler({key: 1});
    const toT = (s: number) => s.toString();
    const fromT = (s: number, t: string) => parseInt(t);
    const shV = sh.xmapView("key", toT, fromT);
    sh.addObserver((next: {key: number}, prev: {key: number}) => {
        expect(prev.key).toBe(1);
        expect(next.key).toBe(2);
        done()
    });
    shV.setState("2");
    expect(shV.getState()).toBe(toT(sh.getState().key))
});

test("xmapview can be used to view a modified value and cleans up after itself if toT returns undefined", done => {
    const sh = new StateHandler({key: [{num: 1, id: "a"}, {num: 2, id: "b"}, {num: 3, id: "c"}]});
    const toT = (ss: {num: number, id: string}[]) => ss.find(s => s.id === "b");
    const fromT = (ss: {num: number, id: string}[], t: {num: number, id: string}) => {
        const idx = ss.findIndex(s => s.id === "b");
        if (idx) {
            ss[idx] = t
        }
        return ss
    };
    const shV = sh.xmapView("key", toT, fromT);
    expect(sh["observers"].length).toEqual(1);
    expect(shV["observers"].length).toEqual(1);
    sh.addObserver((next: {key: {num: number, id: string}[]}) => {
        if (next.key[1].id === "b" && next.key[1].num === 100) {
            expect(next.key).toEqual([{num: 1, id: "a"}, {num: 100, id: "b"}, {num: 3, id: "c"}]);
            // done()
        }
    });
    shV.setState({id: "b", num: 100});
    expect(shV.getState()).toEqual(toT(sh.getState().key));

    sh.updateState(s => {
        s.key.splice(1, 1); // delete id = "b" from the state.
        return s
    });
    expect(sh["observers"].length).toEqual(1);
    expect(shV["observers"].length).toEqual(0);
    done()
});
