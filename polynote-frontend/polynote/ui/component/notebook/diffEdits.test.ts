import {diffEdits} from "./cell";

test("Returned edits should produce the modified string", () => {

    function test(original: string, edited: string) {
        const diff = diffEdits(original, edited);

        let applied = original;
        diff.forEach(edit => applied = edit.apply(applied));

        expect(applied).toEqual(edited);
    }

    function genChar() {
        return Math.floor(Math.random() * 94 + 32);
    }

    function genStr(maxLen: number) {
        const len = Math.floor(Math.random() * maxLen);
        const codes = new Array(len);
        for (let i = 0; i < len; i++) {
            codes[i] = genChar();
        }
        return String.fromCharCode(...codes);
    }

    for (let i = 0; i < 20; i++) {
        test(genStr(100), genStr(100));
    }

})