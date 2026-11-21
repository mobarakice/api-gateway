# Local-stack image: the control-plane distribution (./gradlew :control-plane:installDist) on a JRE.
FROM eclipse-temurin:25-jre
COPY control-plane/build/install/control-plane /opt/control-plane
USER 10001:10001
EXPOSE 9443
ENTRYPOINT ["/opt/control-plane/bin/control-plane"]
