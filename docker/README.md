# Official Docker Images

Polynote publishes official Docker images upon every release. Most users are recommended to use the `latest` tag by 
running `docker pull polynote/polynote:latest`. 

## Published Tags

Every release of Polynote will publish four images:

| Description                   | Tag name                                              |
|-------------------------------|-------------------------------------------------------|
|Base image with Scala 2.11     | `polynote/polynote:${POLYNOTE_VERSION}-2.11`          |
|Base image with Scals 2.12     | `polynote/polynote:${POLYNOTE_VERSION}-2.12`          |
|Spark 2.4 image with Scala 2.11| `polynote/polynote:${POLYNOTE_VERSION}-2.11-spark2.4` |
|Spark 2.4 image with Scala 2.12| `polynote/polynote:${POLYNOTE_VERSION}-2.12-spark2.4` |

Additionally, the `latest` tag is updated to point to `polynote/polynote:${POLYNOTE_VERSION}-2.11-spark2.4`. 

## Usage Instructions

Here are some simple instructions for running Polynote from a Docker image. Please note that these instructions are meant for use in a safe, development environment and should not be used in production. 

> Keep in mind that Polynote, like all notebook tools, allows arbitrary remote code execution. Running it in a Docker container, while mitigating certain possible attack vectors, does not automatically make it safe. 
>
> For this reason, the Docker images we publish do NOT come with any changes to the default configuration. 

Here are some instructions for a simple setup enabling users try out the `latest` Polynote image. 

First, we'll do some setup and create a local directory in which to put our configuration and save our notebooks. 

```
mkdir ~/polynote-docker
cd ~/polynote-docker
```

Next, make a `config.yml` file with the following contents: 

```
listen:
  host: 0.0.0.0

storage:
  dir: /opt/notebooks
  mounts:
    examples:
      dir: examples
```

> The `listen` directive tells Polynote to listen on all interfaces inside the Docker container. 
> While this is probably fine for trying it out, it's not fit for production settings. 
>
> The `storage` directive specifies `/opt/notebooks` as the primary notebook location. It also tells Polynote to 
> mount the examples directory that is present in the Docker distribution as a secondary mount, allowing you to see the
> examples and try them out! 
> Note that changes to the examples might not be persisted onto the host if you follow the instructions below.

Ok, now we are ready to run the Docker container! Use the following command: 

```
docker run --rm -it -p 127.0.0.1:8192:8192 -p 127.0.0.1:4040-4050:4040-4050 -v `pwd`/config.yml:/opt/config/config.yml -v `pwd`/notebooks:/opt/polynote/notebooks/saved polynote/polynote:latest --config /opt/config/config.yml
```

Let's go over what this command does. 

- `-p 127.0.0.1:8192:8192` This binds `127.0.0.1:8192` on the host machine to port `8192` inside the container.
- `-p 127.0.0.1:4040-4050:4040-4050` This binds a range of ports on the host machine so you can see the Spark UI. 
- ``-v `pwd`/config.yml:/opt/config/config.yml`` This mounts the config file in the current directory (the one you just created) into the container at `/opt/config/config.yml`
- ``-v `pwd`/notebooks:/opt/polynote/notebooks/saved`` This mounts the `./notebooks` directory on the host into the container at `/opt/polynote/notebooks/saved`, so that any notebooks you create are saved back to the host, and the example notebooks inside of `/opt/polynote/notebooks/` are still visible to you.
ENV HOME /home/${USER}
- `polynote/polynote:latest` pulls the latest Polynote image
- `--config /opt/config/config.yml` tells Polynote to use the `config.yml` file you created earlier. 

Great! Now just open up Chrome and navigate over to http://localhost:8192/ and you'll see the Polynote UI! You'll see the example folder show up on the left. 
Open it up and run some of the examples! :smile: 

> Note: Changes you make to the example notebooks won't persist, since they aren't mounted to the host.

Try making a new notebook by clicking the new notebook button and typing "my first notebook" into the dialog box. You should see it show up in the notebooks tree!

Now, if you open up a new terminal window and run `ls -l ~/polynote-docker/notebooks`, you should see `my first notebook.ipynb` on your host machine!


# Building the Image

To build the image from the Dockerfiles included here, run the following series of commands from the `docker` directory:

We tag these images with the name `polynote-local`.

## Base image

```sh
# change version to match desired target
export POLYNOTE_VERSION=0.2.13
docker build -t polynote-local --build-arg POLYNOTE_VERSION=$POLYNOTE_VERSION base
```

## Safe image

Choose an existing base image and turn into one that runs without root privileges, with a safe-user (disabled password) named `polly`:

```
# change to any of the base images listed in docker/README.md
export BASE_IMAGE=polynote/polynote:latest
docker build -t polynote-local --build-arg BASE_IMAGE safe
```
