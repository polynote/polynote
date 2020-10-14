# Developing Polynote

Polynote is written in a combination of Python and Scala (for the backend) 
and Typescript (for the frontend ui). You will need the development tools
outlined under [Dependencies](#dependencies) prior to attempting to build from
 source. 

## Dependencies

- JDK 8+
- SBT
- Node.js 13+
- Python 3.7

## Building

- Build the frontend

```
cd polynote-frontend
npm install
npm run dist
```

- Build the backend 

```
sbt +dist
```

# Running

```
cd target/scala-2.12/polynote
./polynote.py
```

