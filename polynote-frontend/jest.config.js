module.exports = {
  preset: 'ts-jest/presets/js-with-ts',
  testMatch: ["**/?(*.)+(spec|test).ts"],
  testPathIgnorePatterns: ['/node_modules/', 'dist'],
  setupFilesAfterEnv: ['./jest.setup.ts', 'fake-indexeddb/auto'],
  transform: {
    "^.+\\.ts$": "ts-jest"
  }
};