module.exports = {
  preset: 'ts-jest/presets/js-with-ts',
  testMatch: ["**/?(*.)+(spec|test).ts"],
  testPathIgnorePatterns: ['/node_modules/', '/dist/'],
  setupFilesAfterEnv: ['./jest.setup.ts', 'fake-indexeddb/auto', 'jest-canvas-mock'],
  transform: {
    "^.+\\.ts$": "ts-jest"
  },
  testEnvironment: 'jsdom',
  reporters: [
      'default',
      ['./vendor/jest-summarizing-reporter', {diffs: true}]
  ]
};