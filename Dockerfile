FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build

WORKDIR /workspace
COPY . .
RUN ./mvnw --batch-mode clean verify

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

ARG APP_UID=1000
ARG APP_GID=1000
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="IssueBot" \
      org.opencontainers.image.source="https://github.com/dbbaskette/IssueBot" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN groupadd --non-unique --gid "${APP_GID}" issuebot \
    && useradd --non-unique --uid "${APP_UID}" --gid issuebot --create-home --shell /usr/sbin/nologin issuebot \
    && install --directory --owner issuebot --group issuebot /home/issuebot/.issuebot /app

COPY --from=build --chown=issuebot:issuebot /workspace/target/issuebot.jar /app/issuebot.jar

USER issuebot
WORKDIR /home/issuebot
EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8090/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/issuebot.jar"]
