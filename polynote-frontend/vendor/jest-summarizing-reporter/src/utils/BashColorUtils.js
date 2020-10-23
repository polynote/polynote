let bgLightRed = colorize("\x1b[101m");
let bgLightGreen = colorize("\x1b[102m");
let black = colorize("\x1b[30m");
let white = colorize("\x1b[1m");
let red = colorize("\x1b[31m");
let green = colorize("\x1b[32m");
let lightRed = colorize("\x1b[91m");
let lightYellow = colorize("\x1b[93m");
let yellow = colorize("\x1b[33m");

function colorize(color) {
  let reset = "\x1b[0m";
  return function(s) {
    return color + s + reset;
  }
}

module.exports = {
  bgLightRed,
  bgLightGreen,
  black,
  white,
  red,
  green,
  lightRed,
  lightYellow,
  yellow
};