let pathModule = require('path');

const regexPathSeparator = pathModule.sep === '/' ? '/' : '\\\\';

function processFullPath(fullPath) {
  const cwd = process.cwd();
  const noCwdPath = fullPath.replace(cwd, "");

  const pathSeparationPattern = new RegExp(`(${regexPathSeparator}.+${regexPathSeparator})(.+)$`);
  const pathSeparationResult = noCwdPath.match(pathSeparationPattern);

  return path({
    path: slash(pathSeparationResult[1]),
    file: slash(pathSeparationResult[2])
  })
}

function path({path, file}) {
  return {
    path,
    file
  }
}

function slash(s) {
  return s.replace(/\\/g, "/")
}

module.exports = {
  processFullPath
};