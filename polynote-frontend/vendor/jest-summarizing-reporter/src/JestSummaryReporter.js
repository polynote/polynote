const { red, green, black, bgLightGreen, bgLightRed, lightRed, lightYellow, white, yellow } = require('./utils/BashColorUtils');
const timeObj = require('./utils/TimeUtils').timestampToTimeObject;
const { processFullPath } = require('./utils/PathUtils');
const { println, print } = require('./utils/PrintUtils');
const { ifAny, not } = require('./utils/FunctionalUtils');

class JestSummaryReporter {
  constructor(globalConfig, options) {
    this.globalConfig = globalConfig;
    this.options = options;
  }

  onRunComplete(contexts, results) {
    const { snapshot } = results;
    println(2);

    print(lightYellow('Summary reporter output:'));
    println(2);

    printPassedSuites(results);
    if (this.options.diffs) {
      printFailedTestDiffs(results);
    }
    printFailedSuites(results);
    if (snapshot.failure) {
      printUncheckedSnapshotsSummary(snapshot);
    }
    printSummary(results);
  }
}


function printFailedTestDiffs(results) {
  results.testResults
    .filter(suite => suite.failureMessage)
    [ifAny](failedSuites => {
      print('Failed test diffs:');

      failedSuites
        .map(suite => {
          return {
            path: processFullPath(suite.testFilePath),
            msg: suite.failureMessage,
            toString() {
              return `${black(bgLightRed(" FAIL "))} ${this.path.path}${white(this.path.file)}\n${this.msg}`;
            }
          }
        })
        .forEach(failedSuite => print(failedSuite.toString()));

      println();
    });
}


function printSummary(results) {
  let {
    numTotalTestSuites: totalSuites,
    numPassedTestSuites: passedSuites,
    numPendingTestSuites: pendingSuites,
    numTotalTests: totalTests,
    numPassedTests: passedTests,
    numFailedTests: failedTests,
    numRuntimeErrorTestSuites: erroredTests,
    snapshot
  } = results;
  let failedSuites = totalSuites - passedSuites - pendingSuites;
  let failed = failedTests > 0 || erroredTests > 0 || snapshot.failure;

  print('Summary:');
  print(`Suites: ${failed ? lightRed(failedSuites) : green(passedSuites)}/${white(totalSuites)}`);
  print(`Tests:  ${failed ? lightRed(failedTests) : green(passedTests)}/${white(totalTests)}`);
  print(`Time:   ${timeObj(Date.now() - results.startTime)}`);
}


function printPassedSuites(results) {
  results.testResults
    .filter(not(suiteFailed))
    [ifAny](passed => {
      print('Passed suites:');
      passed.forEach(printAsSuccess);
      println(2);
    });

  function printAsSuccess(suite) {
    let path = processFullPath(suite.testFilePath);
    print(`${bgLightGreen(black(" PASS "))} ${path.path + path.file}`);
  }
}


function printFailedSuites(results) {
  results.testResults
    .filter(suiteFailed)
    [ifAny](failures => {
      print('Failed suites:');
      failures.forEach(printAsFailure);
      println(2);
    });

  function printAsFailure(suite) {
    let path = processFullPath(suite.testFilePath);

    print(`${bgLightRed(black(" FAIL "))} ${path.path}${white(path.file)}`);
    printFailedTestNames(suite);
  }

  function printFailedTestNames(suite) {
    suite.testResults
      .filter(test => test.status === "failed")
      .forEach(test =>
        print(`${red('  ? ')}${yellow(test.fullName)}`)
      );
  }
}

function printUncheckedSnapshotsSummary(snapshot) {
  print(`${bgLightRed(black(" UNUSED SNAPSHOTS "))}  found. 'npm t -- -u' to remove them`);
  snapshot.uncheckedKeysByFile
    .forEach(printAsFailure);
  println(2);

  function printAsFailure(file) {
    let path = processFullPath(file.filePath);
    print(`${path.path}${white(path.file)}`);
  }
}

function suiteFailed(suite) {
  return suite.numFailingTests > 0 || suite.failureMessage;
}


module.exports = JestSummaryReporter;