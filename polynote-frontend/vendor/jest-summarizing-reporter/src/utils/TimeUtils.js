const { div } = require('./MathUtils');

const sd = 1000;
const md = sd*60;
const hd = md*60;

function timeObject(h, m, s, ms = 0) {
  return {
    h, m, s, ms,
    get timestamp() {
      return h * hd + m * md + s * sd + ms;
    },
    toString() {
      let h  = cond(this.h,  "h");
      let m  = cond(this.m,  "m");
      let s  = cond(this.s,  "s");
      let ms = cond(this.ms, "ms");

      return [ms, s, m, h]
        .filter(it => it)
        .reduce((a, i) => (a === "" ? i : i + " " + a), "");

      function cond(v, l) {
        return v ? v + l : "";
      }
    }
  }
}

function timestampToTimeObject(timestamp) {
  let h = div(timestamp, hd);
  timestamp -= h*hd;
  let m = div(timestamp, md);
  timestamp -= m*md;
  let s = div(timestamp, sd);
  timestamp -= s*sd;

  return timeObject(h, m, s, timestamp);
}

module.exports = {
  timestampToTimeObject
};