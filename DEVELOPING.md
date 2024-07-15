# Developing Polynote

Polynote is written in a combination of Python and Scala (for the backend) 
and Typescript (for the frontend ui). You will need the development tools
outlined under [Dependencies](#dependencies) prior to attempting to build from
 source. Alternatively, you can use the 
 [development docker image](https://github.com/polynote/polynote/tree/master/docker#dev-image).
 

## Dependencies

- JDK 8
- SBT
- Node.js 13+
- Python 3.7

## Building

- Build the frontend

```
cd polynote-frontend
npm install
npm run build
```

- Return to the root directory

```
cd ..
```

- Build the distribution

```
sbt dist
```

# Running

```
cd target/dist/polynote
./polynote.py
```

# Running with IntelliJ
To run your app using IntelliJ, navigate to `Run -> Edit Configuration` and create a new application configuration 
performing the following steps:

- Under `Name`, enter `polynote.Main`. 
- Select `Modify Options` -> Select `Add VM Options` and `Add depdendencies with provided scope to classpath`
- Select `Build and Run` -> Select `cp-spark`
- Under `VM Options`, enter `-Djava.library.path=<path-to-jep>`
  - Ex: `-Djava.library.path=/opt/homebrew/lib/python3.9/site-packages/jep`
- Under 'Main Class', enter `polynote.Main` 
- Under `Program Arguments`, enter `--watch`

## Troubleshooting with IntelliJ 
Occasionally, IntelliJ will not play nicely with your run configuration. Below is a list of common issues we've noticed - 
please [submit an issue](https://github.com/polynote/polynote/issues/new/choose) if you encounter any other problems.

- None of my local notebooks load/none of the cells load 
  - This is an issue with your local build. To fix it, go to `Build -> Rebuild Project` in IntelliJ. 
- Polynote crashes with a Fiber issue at runtime. 
  - This is most likely because IntelliJ sometimes creates its own run configuration depending on how you launch your
    application. To fix this, go to `Run` in your bottom bar and select `Modify Run Configuration` and apply the 
    above settings. 
