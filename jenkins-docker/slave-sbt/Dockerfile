FROM gerritforge/gerrit-ci-slave-bazel:debian-buster-80.0.3987.149

# SBT Setup
RUN apt-get update && \
    apt-get -y install apt-transport-https && \
    echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 642AC823 && \
    apt-get update && \
    apt-get -y install --no-install-recommends sbt && \
    rm -rf /etc/apt/sources.list.d/* && \
    rm -rf /var/lib/apt/lists/*

# SBT warm-up
RUN cd /tmp && sbt new sbt/scala-seed.g8 --name=hello
RUN echo 'addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")' >> /tmp/hello/project/plugins.sbt
RUN chown -R jenkins: /tmp/hello

USER jenkins
RUN cd /tmp/hello && sbt compile test assembly

USER root
