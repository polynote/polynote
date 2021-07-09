#!/usr/bin/env bash
set -ex
# Ignore everything the Jupyter launcher tells us
# Use the JUPYTERHUB_SERVICE_PREFIX as the base_uri since we need to match the reverse proxy set up by zero-to-jupyterhub.
# Also require the jupyter tokens are present
echo "
ui:
  base_uri: $JUPYTERHUB_SERVICE_PREFIX
security:
  auth:
    provider: hub
    config:
      JUPYTERHUB_API_URL: ${JUPYTERHUB_API_URL}
      JPY_API_TOKEN: ${JPY_API_TOKEN}
      permissions:
        ${JUPYTERHUB_USER}: all
" >> /config.yml
./polynote/polynote.py --config /config.yml
