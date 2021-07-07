FROM holdenk/polynote:dev

# Being root again
USER root
# A script to ignore everything we tell it. I'm sure that's useful
COPY scripts/jupyter-fake-launch.sh  ./polynote/
# Copy config file
COPY --chown=${NB_USER}:${NB_USER} docker/jupyter/config.yml /config.yml
# Back to being a safe-ish user
USER ${NB_USER}
# Use our custom launch script
ENTRYPOINT ["./polynote/jupyter-fake-launch.sh"]
