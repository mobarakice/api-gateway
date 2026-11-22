# Local-stack image: the jvm-prod distribution (./gradlew :gateway-app:installDist) on a JRE.
# Production image: *.native.Dockerfile. This one is the jvm-prod comparison target (M10) and compose.jvm.yml.
FROM eclipse-temurin:25-jre
COPY gateway-app/build/install/gateway /opt/gateway
USER 10001:10001
EXPOSE 8080 9901
ENTRYPOINT ["/opt/gateway/bin/gateway"]
