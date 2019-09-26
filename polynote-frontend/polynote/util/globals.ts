/**
 * Global overrides go here. This file *must* be in the global namespace (that is, no import/exports allowed here).
 *
 * Everything defined in this file will be available everywhere, without importing.
 *
 * More info about Global modules: https://basarat.gitbooks.io/typescript/content/docs/project/modules.html
 *
 * To add to the _module_ namespace, add definitions to modules.ts.
 */

/**
 * Support concat on arrays of different types. See: https://github.com/Microsoft/TypeScript/issues/26378
 */
interface Array<T> {
    concat<U>(...items: (U | ConcatArray<U>)[]): Array<T|U>;
}
