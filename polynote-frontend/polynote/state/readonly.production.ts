export function __readOnlyProxyObject<S extends Object>(obj: S): S {
    return obj;
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
    return obj;
}