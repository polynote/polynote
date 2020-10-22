---
title: Using the Database Storage Option
layout: docs
---

# How to use the Database Storage Option

To use a Database as storage for Polynote notebooks instead of a file system, a Postgres database needs to be stood up. A companion Postgres database exists as an example for testing against as a docker image, in the docker directory. And the database DDL in that directory, `db_ddl.sql`, for the schema and table can be used independently if you wish to set up your own Postgres instance.

To use the example docker database instance, run the shell script `create-db.sh` in the `./docker/database` directory. It will download and start up a docker image for Postgres and install the schema and table to use for notebook storage. Once that completes then copy the `config-template.yml` file to `config.yml` and uncomment the `database_config` configuration block. That is already configured to work against the example Postgres database docker image.

To use Polynote against the example database for storage, start up Polynote with the config option of `--config config.yml`. A few of the example Polynote notebooks are preloaded into the example database to play with, under the `polynote` folder. Polynote will behave the same as when working against a file system for storage, except everything is stored in the database. Sub-folders can be created just as with a normal file system as well. The path for notebooks is stored in the database.

When using a Database as storage, the ability to store notebooks in a file system is disabled. Notebooks can only be stored in a database when this option is used.

# Use of environment variables instead of config.yml

For some environments it may be desirable to configure the database connection via environment variables. Following are a list of the environment variables to do so. If they exist in the environment, then any relative options in the `config.yml` file will be ignored.

* DATABASE_USER=`postgres`
* DATABASE_PASSWORD=`polynote`
* DATABASE_HOST=`localhost`
* DATABASE_PORT=`5432`
* DATABASE_NAME=`postgres`


