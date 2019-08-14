// BREAKOUT (utils?)
export function maxId(cells) {
    let max = -1;
    cells.forEach(cell => {
        if (cell.id > max) {
            max = cell.id;
        }
    });
    return max;
}