
//////////////////////////////////////
// Constructors for read-only views //
//////////////////////////////////////

/**
 * Read-only view of any value
 */
function readOnlyProxyView<S>(value: S): S {
    if (value === null || value === undefined)
        return value as S;
    switch (typeof value) {
        case "object":
            if (Object.isFrozen(value))
                return value as S;
            return __readOnlyProxyObject(value);
        default: return value as S;
    }
}

function mutatingMethod(name: string): () => never {
    return () => {
        throw new Error(`Attempted to invoke mutating method ${name} on an immutable view`)
    }
}

// global proxy handler – no caching of member proxies
const proxyMethods = Object.freeze({
    refreshStateView: (added: PropertyKey[], removed: PropertyKey[]) => {},
    splice: mutatingMethod('splice'),
    push: mutatingMethod('push'),
    pop: mutatingMethod('pop'),
    shift: mutatingMethod('shift'),
    unshift: mutatingMethod('unshift')
}) as any

const isProxy: unique symbol = Symbol.for("isProxy")
const proxyTarget: unique symbol = Symbol.for("proxyTarget")

const proxyHandler = Object.freeze({
    set(target: any, prop: PropertyKey) {
        throw new Error(`Attempted to set ${prop.toString()} on an immutable view`);
    },
    get(target: any, prop: PropertyKey) {
        if (prop === isProxy)
            return true;
        if (prop === proxyTarget)
            return target;
        if (prop in proxyMethods)
            return proxyMethods[prop];

        const value = target[prop];
        if (value === null || value === undefined) {
            return value;
        }
        return readOnlyProxyView(value);
    }
});

/**
 * Creates a read-only proxy of an object, which throws if you try to set any property or call any mutating array
 * methods. Only used during development & testing.
 *
 * In a production build, the mutable objects are used directly (see readonly.production.ts) thus bypassing the proxy
 * overhead.
 *
 * This function shouldn't be used outside of the state module, so it's prefixed with __
 */
export function __readOnlyProxyObject<S extends Object>(obj: S): S {
    return new Proxy(obj, proxyHandler);
}

/**
 * If the given object is a proxy over a mutable object, return the mutable object that underlies it.
 *
 * In a production build, there aren't any proxies (see readonly.production.ts). So the production version of this
 * function simply returns the argument.
 *
 * This function shouldn't be used outside of the state module, so it's prefixed with __
 */
export function __getProxyTarget<S>(obj: S): S {
    if (obj && (obj as any)[isProxy]) {
        return (obj as any)[proxyTarget] as S;
    }
    return obj;
}

/**
 * Read-only view of an object, no proxies, caches members.
 * This currently isn't used; the build should eliminate it. It's here in case we want to develop a read-only view for
 * production runtime which isn't based on proxies. Currently, arrays make this difficult.
 */
function readOnlyObject<S extends object>(obj: S): S {
    const view: S = {} as S;
    const props: PropertyDescriptorMap = {};
    let memberViews: S = {} as S;

    function mkProp(prop: keyof S) {
        return {
            enumerable: true,
            get() {
                if (!(prop in memberViews))
                    (memberViews as any)[prop] = readOnlyProxyView(obj[prop]);
                return memberViews[prop];
            }
        }
    }

    const removedProp = Object.freeze({value: undefined, enumerable: false})

    for (let key of Object.keys(obj)) {
        props[key] = mkProp(key as keyof S);
    }
    Object.defineProperties(view, props);

    if (obj instanceof Array) {
        Object.defineProperties(view, {
            length: {
                get() {
                    return obj.length;
                }
            },
            [Symbol.iterator]: {
                get() {
                    return function* () {
                        for (const prop of Object.keys(obj)) {
                            yield view[prop as keyof S];
                        }
                    }
                }
            }
        })
    }


    const refreshStateView = function (added?: (keyof S)[], removed?: (keyof S)[]): void {
        if (obj instanceof Array) {
            memberViews = {} as S;
            let i = 0;
            for (i = 0; i < obj.length; i++) {
                if (!(i in view)) {
                    Object.defineProperty(view, i, mkProp(i as keyof S));
                }
            }
            const maxRemoved = obj.length + (removed?.length ?? 0);
            for (; i < maxRemoved; i++) {
                if (i in view) {
                    Object.defineProperty(view, i, removedProp);
                }
            }
        } else {
            if (added) {
                for (const key of added) {
                    const prop = key as keyof S;
                    delete memberViews[prop];
                    if (!(key in view)) {
                        Object.defineProperty(view, prop, mkProp(prop));
                    }
                }
            }

            if (removed) {
                for (const key of removed) {
                    const prop = key as keyof S;
                    delete memberViews[prop];
                    Object.defineProperty(view, prop, removedProp);
                }
            }
        }
    }

    Object.defineProperty(view, 'refreshStateView', {value: refreshStateView});
    Object.setPrototypeOf(view, Object.getPrototypeOf(obj));

    return view as S;
}