const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const path = require('path');

module.exports = {
  entry: './polynote/main.js',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'app.[contenthash].js'
  },
  module: {
    rules: [{
      test: /\.css$/,
      use: ['style-loader', 'css-loader']
    }]
  },
  plugins: [
    new MonacoWebpackPlugin({
	    languages: ['clojure', 'java', 'markdown', 'python', 'r', 'ruby', 'json', 'sql', 'swift']
    }),
    new HtmlWebpackPlugin({
        template: './index.html'
    }),
    new CopyWebpackPlugin([
      // 'index.html',
      { from: 'style', to: 'style' },
      { from: 'favicon.ico', to: 'favicon.ico' },
    ])
  ],
  mode: "development"
};
