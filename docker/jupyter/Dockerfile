FROM holdenk/polynote:dev

# Being root again
USER root
# Hack for debugging
RUN apt-get update -y && apt-get install -y nano procps telnet
# A script to ignore everything we tell it. I'm sure that's useful
COPY scripts/jupyter-fake-launch.sh  ./polynote/
# Copy config file
COPY --chown=${NB_USER}:${NB_USER} docker/jupyter/config.yml /config.yml
# Examples doesn't seem to be present by default.
COPY --chown=${NB_USER}:${NB_USER} docs/examples ./notebooks/
# This is weird, probably should be added to the dev & base containers.
COPY --chown=${NB_USER}:${NB_USER} polynote-* ./polynote/
# Back to being a safe-ish user
USER ${NB_USER}
# Use our custom launch script
ENTRYPOINT ["./polynote/jupyter-fake-launch.sh"]
