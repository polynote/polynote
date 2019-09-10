// Type definitions for `diff`.

export interface DiffOptions {
    callback?: (...args: any[]) => void,
    comparator?: (l: string, r: string) => boolean
    ignoreCase?: boolean
}

interface Diff {
    diff(a: string, b: string, options?: (...args: any[]) => void | DiffOptions): string
}

export default function Diff(): Diff
