module.exports = {
  verbose: false,
  reporters: [
    // "default",
    ["./src/JestSummaryReporter", {diffs: true}],
    // "./src/JestSummaryReporter",
  ]
};