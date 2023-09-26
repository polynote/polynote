const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const webpack = require('webpack');
const path = require('path');
const {exec} = require("child_process");

module.exports = {
  entry: './polynote/main.ts',
  devtool: 'source-map',
  output: {
    path: path.resolve(__dirname, 'dist/static'),
    filename: 'app.[contenthash].js',
    publicPath: 'static/',
    hashFunction: 'xxhash64'
  },
  module: {
    rules: [{
      test: /\.css$/,
      use: ['style-loader', 'css-loader']
    }, {
      test: /\.ts$/,
      use: ['ts-loader'],
      exclude: /node_modules/
    }, {
      test: /\.ttf$/,  // these are bundled with monaco
      use: ['file-loader']
    }]
  },
  resolve: {
    extensions: [".ts", ".js"]
  },
  plugins: [
    // when building for release, don't do any readonly proxy things – the assumption is that if readonly proxies
    // are happening during development & testing, there's no need to pay the performance hit to guard against it
    // in production.
    new webpack.NormalModuleReplacementPlugin(
        /polynote\/state\/readonly\.ts/,
        './readonly.production.ts'
    ),
    new MonacoWebpackPlugin({
	    languages: ['clojure', 'java', 'markdown', 'python', 'r', 'ruby', 'json', 'sql', 'swift']
    }),
    new HtmlWebpackPlugin({
        template: './index.html'
    }),
    new CopyWebpackPlugin({
      patterns: [
        { from: 'style', to: 'style' },
        { from: 'vendor', to: 'vendor' },
        { from: 'favicon.ico', to: 'favicon.ico' },
        { from: 'favicon.svg', to: 'favicon.svg' },
      ]
    }),
    {
      apply: (compiler) => {
        compiler.hooks.watchRun.tap('HtmlWebpackPlugin', (compilation) => {
          if (compilation.modifiedFiles) {
            let changedFiles = Array.from(compilation.modifiedFiles);
            if (changedFiles.some(path => path.includes(".less"))) {
              exec('./build_style', (err, stdout, stderr) => {
                if (stdout) process.stdout.write(stdout);
                if (stderr) process.stderr.write(stderr);
              });
            }
          }
        });
      }
    }
  ],
  mode: "development"
};
