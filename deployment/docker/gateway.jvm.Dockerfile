# Local-stack image: the jvm-prod distribution (./gradlew :gateway-app:installDist) on a JRE.
# The production image (native binary, distroless) is built in M9.
FROM eclipse-temurin:25-jre
COPY gateway-app/build/install/gateway /opt/gateway
USER 10001:10001
EXPOSE 8080 9901
ENTRYPOINT ["/opt/gateway/bin/gateway"]
