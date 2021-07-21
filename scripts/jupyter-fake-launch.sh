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
      jupyterhub_api_url: \"${JUPYTERHUB_API_URL}\"
      jpy_api_token: ${JPY_API_TOKEN}
      jupyterhub_client_id: ${JUPYTERHUB_CLIENT_ID}
      base_uri: $JUPYTERHUB_SERVICE_PREFIX
      rdr_url: ${JUPYTERHUB_SERVICE_PREFIX}oauth_callback # Jupyter won't let us do cool names :(
      permissions:
        ${JUPYTERHUB_USER}:
          - all
" >> /config.yml
./polynote/polynote.py --config /config.yml
