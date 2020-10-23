const ifAny = Symbol("Function to be performed on array elements if there is at least one");
Array.prototype[ifAny] = function(fn) {
  if (this.length > 0) {
    fn(this);
  }
  return this;
};


function not(fn) {
  return function(...params) {
    return !fn(...params);
  }
}


module.exports = {
  ifAny,
  not
};