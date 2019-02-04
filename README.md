# polynote

Polynote is an experimental polyglot notebook environment. Currently, it supports Scala (with or without Spark) and
Python.

## Why?

Current notebook solutions, like Jupyter and Zeppelin, are lacking in some fundamental features:

- *Code editing* – the code editing capabilities in most notebook tools leave plenty to be desired. Why can't a notebook
  tool have modern editing capabilities like those you'd find in an IDE? Polynote provides useful autocomplete,
  parameter hints, and more – we're planning to add even more features, like jump-to-definition.
- *Text editing* – you can use the WYSIWYG editor for composing text cells, so you'll know what the text will look like as
  you're writing. TeX equations are also supported.
- *Multi-language support* – Polynote allows you to mix multiple languages in one notebook, while sharing definitions
  seamlessly between them.
- *Runtime insight* – Polynote tries to keep you informed of what's going on at runtime:
    - The tasks area shows you what the kernel is doing at any given time.
    - The symbol table shows you what variables and functions you've defined, so you don't have to scroll around to remind yourself.
    - Compile failures and runtime exceptions are highlighted in the editor (for supported languages), so you can see exactly what's going wrong.

## Usage

### Running locally

You can run Polynote locally in IntelliJ by running `polynote/server/SparkServer.scala`. In the Run Configuration, 
make sure to select the "Include dependencies with Provided Scope" option to load Spark. 

To use the Python kernel, you'll want to [install jep](https://github.com/ninia/jep/wiki/Getting-Started#installing-jep). 
You'll need to add the jep library to a place where Java can find it. 

On OS X, you will need to do something like:
    
    ln -sf /usr//local/lib/python3.7/site-packages/jep/libjep.jnilib /Library/Java/Extensions/libjep.jnilib    

On Linux, you will need to do something like:

    ln -sf /usr/local/lib/python2.7/dist-packages/jep/libjep.so /usr/lib/libjep.so
    
You may also want to check out the instructions for your system as described in the 
[jep docs under Operating System Specifics](https://github.com/ninia/jep/wiki). 

You will also need to install [`jedi`](https://jedi.readthedocs.io/en/latest/) for completions

    pip install jedi

### Running remotely

We have some scripts that make it easy to install and run Polynote on a remote machine. They are located in the `scripts/`
directory. *Make sure to read them before using!* :)

The scripts read a few environment variables that you can set: 

    
    REMOTE_HOST         (required) hostname, make sure you have SSH access to this host
    REMOTE_USER         (default: root) the username to use with SSH
    REMOTE_DIR          (default: /root/polynote) the location in which to install Polynote
    INSTALL_ONLY        (default: 0) if nonzero, only install but don't run Polynote

The best place to start would be to run `installAndRun.sh`:

    env REMOTE_HOST=me.myhost.mytld ./scripts/installAndRun.sh

#### Dependencies

The scripts won't install anything on your local machine, so you will need to make sure you have: 

- Assuming you have [homebrew](https://docs.brew.sh/Installation) (including the `xcode-select --install` step)
- [sbt](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Mac.html) `brew install sbt`
- [npm](https://www.npmjs.com/get-npm) `brew install npm`
  - Inside the `polynote-frontend` dir:
    - [webpack](https://webpack.js.org/) `npm install webpack` 
    - [markdown-it](https://www.npmjs.com/package/markdown-it) `npm install markdown-it`
    
## Documentation

TODO

## Contributing

TODO

