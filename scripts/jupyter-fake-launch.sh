#!/usr/bin/env bash
set -ex
# Ignore everything the Jupyter launcher tells us
# Use the JUPYTERHUB_SERVICE_PREFIX as the base_uri since we need to match the reverse proxy set up by zero-to-jupyterhub.
echo "
ui:
  base_uri: $JUPYTERHUB_SERVICE_PREFIX
" >> /config.yml
./polynote/polynote.py --config /config.yml
