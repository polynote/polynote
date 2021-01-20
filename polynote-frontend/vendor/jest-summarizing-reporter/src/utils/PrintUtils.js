function println(times = 1) {
  for(let i = 0; i < times; i++) {
    print('');
  }
}

function print(...s) {
  console.log(...s);
}


module.exports = {
  println,
  print
};