# Docker image for Jenkins Enterprise by CloudBees master

FROM apemberton/jenkins-haproxy
MAINTAINER Andy Pemberton <apemberton@cloudbees.com>

RUN apt-get update && apt-get install -y --no-install-recommends \
    groovy

EXPOSE 80

ADD /jocproxy.groovy /jocproxy.groovy

# CMD ["groovy", "/jocproxy.groovy", "-p", "/operations-center"]

ENTRYPOINT ["groovy", "/jocproxy.groovy"]
CMD ["-p", "/operations-center"]
