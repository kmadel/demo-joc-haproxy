# Docker image for Jenkins Enterprise by CloudBees master

FROM apemberton/jenkins-base
MAINTAINER Andy Pemberton <apemberton@cloudbees.com>

# install haproxy
# USER jenkins
# WORKDIR /usr/lib/jenkins

RUN apt-get update && apt-get install -y --no-install-recommends \
    haproxy \
    rsyslog

EXPOSE 80 49187

CMD ["/bin/bash"]
