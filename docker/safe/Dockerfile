ARG BASE_IMAGE
FROM $BASE_IMAGE

# create (safe)user with a home directory
ENV UID 1000
ENV NB_USER polly

RUN adduser --disabled-password \
    --gecos "Default user" \
    --uid ${UID} \
    ${NB_USER}

# allow user access to the WORKDIR
RUN chown -R ${USER}:${USER} /opt/

# start image as (safe)user
USER ${NB_USER}
