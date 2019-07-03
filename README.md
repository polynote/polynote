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

### Alpha user instructions

Alpha users should use our distribution zip file to deploy Polynote. You can go straight to [these instructions](#running-locally-using-scripts)

### Maintainer instructions

Maintainers can run Polynote in three main ways: locally through the IDE, locally using the run scripts, and remotely using the run scripts. 

#### Running locally with IntelliJ

You can run Polynote locally in IntelliJ by running `polynote/server/SparkServer.scala`. In the Run Configuration, 
make sure to select the "Include dependencies with Provided Scope" option to load Spark. There are more 
[tips for developers here.](#development-tips)

To use the Python kernel, you'll need to install [`jep`](https://github.com/ninia/jep) and [`jedi`](https://jedi.readthedocs.io/en/latest/). 
You can use `scripts/install-jep.sh` to install both on your machine. 
It will also echo out the value you should set your `LD_LIBRARY_PATH` to for `jep` to work.
You can set the `LD_LIBRARY_PATH` variable to that value in your IntelliJ Run Configuration. 

##### Manual Installation

Alternatively, you can install `jep` and `jedi` manually. 
Check out the [`jep` installation instructions](https://github.com/ninia/jep/wiki/Getting-Started#installing-jep). 

On OS X, you will need to do something like:
    
    ln -sf /usr//local/lib/python3.7/site-packages/jep/libjep.jnilib /Library/Java/Extensions/libjep.jnilib    

On Linux, you will need to do something like:

    ln -sf /usr/local/lib/python3.7/dist-packages/jep/libjep.so /usr/lib/libjep.so
    
You may also want to check out the instructions for your system as described in the 
[jep docs under Operating System Specifics](https://github.com/ninia/jep/wiki). 

You can install `jedi` with

    pip3 install jedi
    
    
### Running locally using scripts

Note that you will need `spark-submit` to be available on your machine! If it's not installed (e.g., on your laptop), 
try running [with IntelliJ instead](#running-locally-with-intellij). 

Get your hands on a Polynote distribution zip file [(or follow these instructions to create your own)](#making-a-distribution). 

Once you have your distribution, you can unzip it into the directory with your notebooks:

    unzip -j polynote-distribution.zip

`unzip` will prompt you to overwrite any files you might have in that directory that conflict. If you have created your 
own `config.yml`, make sure not to overwrite it when you unzip!

Once you've unzipped the file, you can run Polynote: 

    source ./install-jep.sh  # you can run even if you've already installed Jep
    ./run.sh

Make sure you have installed the [runtime](#running-locally-with-intellij) dependencies (especially `LD_LIBRARY_PATH`)!

### Running remotely

We have some scripts that make it easy to install and run Polynote on a remote machine. They are located in the `scripts/`
directory. *Make sure to read them before using!* :)

The scripts read a few environment variables that you can set: 

    
    REMOTE_HOST         (required) hostname, make sure you have SSH access to this host
    REMOTE_USER         (default: root) the username to use with SSH
    REMOTE_DIR          (default: /root/polynote) the location in which to install Polynote

First, like when [running locally](#running-locally-using-scripts), get your hands on a Polynote distribution zip file 
[(or follow these instructions to create your own)](#making-a-distribution). 

Now, point `POLYNOTE_DIST` to the location of the distribution and run `publish-distribution.sh`:

    env REMOTE_HOST=me.myhost.mytld POLYNOTE_DIST=/home/me/polynote-distribution.zip ./scripts/publish-distribution.sh

This will copy the distribution over to `/root/polynote/polynote-distribution.zip` (if you want it somewhere else, make 
sure to set `REMOTE_DIR`).

Next, run `run-remotely.sh`: 

    env REMOTE_HOST=me.myhost.mytld ./scripts/run-remotely.sh

This will unzip the distribution and set up a [`tmux`](https://github.com/tmux/tmux) session which runs it on your 
remote machine. Polynote will start and you should see a log that looks like: 

    Running on http://1.2.3.4:8192

Click on or copy that link to your browser and you're all set!

Running with `tmux` ensures that a network hiccup won't kill your Polynote session, even if it kills your SSH connection. 
If your SSH session was lost, you can get back to Polynote by SSHing back into your machine and running `tmux a`. To close
Polynote, you can simply use `Ctrl-C` as usual (as long as Polynote is in the foreground).

You can read about using `tmux` [here](https://hackernoon.com/a-gentle-introduction-to-tmux-8d784c404340).

#### Dependencies

The scripts won't install anything on your local machine, so you will need to make sure you have: 

- Assuming you have [homebrew](https://docs.brew.sh/Installation) (including the `xcode-select --install` step)
- [sbt](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Mac.html) `brew install sbt`
- [npm](https://www.npmjs.com/get-npm) `brew install npm`
  - Inside the `polynote-frontend` dir:
    - Running `npm install` should be all you need to do. However, if that doesn't work for some reason, try running:
      - [webpack](https://webpack.js.org/) `npm install webpack` 
      - [markdown-it](https://www.npmjs.com/package/markdown-it) `npm install markdown-it`
    
## Configuration

Polynote can be configured with a YAML configuration file. By default, a file named `config.yml` present in Polynote's
current directory will be read. You can point Polynote somewhere else using the `--config` or `-c` flag. 

Check out [this template](./config-template.yml) to see the sort of things you can configure. 

Note that configurations that affect notebook behavior, such as dependency, repository or spark configuration, are stored
inside each notebook's metadata upon creation. This means that changing these configurations in your config file will 
not affect existing notebooks - only new notebooks that are created while this configuration is active. 

*Caveat regarding spark configuration*: Currently spark notebooks share a SparkSession which is created on the first time
any notebook is run. This means that the first notebook you run will set the Spark configuration for the entire lifetime
of the Polynote server process. This will be resolved by [this issue](https://github.com/polynote/polynote/issues/101).
    
## Development tips

### Debug logging

To turn on debug logging, set the `POLYNOTE_DEBUG` env variable to `true` or run Polynote with `-Dpolynote.debug=true`. 

### UI development

To watch UI assets and reload them after making changes, run Polynote with the `--watch` flag and then run:

    cd polynote-frontend/
    npm run watch
    
Now, the UI will get packaged every time you make a change, so you can run Polynote and load new UI code by simply 
refreshing the browser. 

### Making a Distribution

First, make sure you have all the [dependencies](#dependencies). 

To create a distribution, simply run 

    ./scripts/make-distribution.sh
    
You can add a `config.yml` file to this distribution by placing a file called `dist-config.yml` into the `scripts` folder. 
This file gets packaged into the distribution as `config.yml`. It's a good place to put environment-specific settings, 
such as default Spark configs, internal Maven/Ivy repos, etc. 

To create and run a distribution on a remote machine all in one go, you can use the handy `./scripts/e2e.sh` script:

    env REMOTE_HOST=me.myhost.mytld ./scripts/e2e.sh

### Releasing

1. Update version numbers. Currently this is a manual process. You'll need to update `build.sbt` and `make-distribution.sh` 
   (and maybe others, so make sure to grep for the version string just in case)
2. Update the changelog
3. Commit and tag the commit with the new version
4. Push the commit and tag. You can push directly to master.
5. Release a new version by [making a distribution](#making-a-distribution) and publishing it.

## Documentation

TODO

## Contributing

TODO

