const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const path = require('path');

module.exports = {
  entry: './polynote/main.ts',
  devtool: 'source-map',
  output: {
    path: path.resolve(__dirname, 'dist/static'),
    filename: 'app.[contenthash].js',
    publicPath: 'static/'
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
    new MonacoWebpackPlugin({
	    languages: ['clojure', 'java', 'markdown', 'python', 'r', 'ruby', 'json', 'sql', 'swift']
    }),
    new HtmlWebpackPlugin({
        template: './index.html'
    }),
    new CopyWebpackPlugin([
      { from: 'style', to: 'style' },
      { from: 'vendor', to: 'vendor' },
      { from: 'favicon.ico', to: 'favicon.ico' },
    ])
  ],
  mode: "development"
};
