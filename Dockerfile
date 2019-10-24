FROM oracle/graalvm-ce


ENV POLYNOTE_VERSION 0.2.8
ENV POLYNOTE_NAME polynote
ENV POLYNOTE_URL https://github.com/polynote/polynote/releases/download/$POLYNOTE_VERSION/${POLYNOTE_NAME}-dist.tar.gz
ENV POLYNOTE_HOME /opt/$POLYNOTE_NAME

ENV CLEANIMAGE_VERSION 1.0
ENV CLEANIMAGE_URL https://raw.githubusercontent.com/LolHens/docker-cleanimage/$CLEANIMAGE_VERSION/cleanimage


ADD ["$CLEANIMAGE_URL", "/usr/local/bin/"]
RUN chmod +x "/usr/local/bin/cleanimage"

RUN yum install -y \
      python3-devel \
 && pip3 install \
      jep \
      jedi \
      virtualenv \
 && cleanimage

RUN cd /opt \
 && curl -L $POLYNOTE_URL | tar -xzf -


WORKDIR $POLYNOTE_HOME

COPY ["entrypoint", "/entrypoint"]
RUN chmod 755 /entrypoint
ENTRYPOINT ["/entrypoint"]

CMD ./polynote
