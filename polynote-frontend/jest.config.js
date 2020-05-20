module.exports = {
  preset: 'ts-jest/presets/js-with-ts',
  testMatch: ["**/?(*.)+(spec|test).ts"],
  testPathIgnorePatterns: ['/node_modules/', 'dist'],
  setupFilesAfterEnv: ['./jest.setup.ts'],
  transform: {
    "^.+\\.ts$": "ts-jest"
  }
};