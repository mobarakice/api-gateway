# syntax=docker/dockerfile:1.7
# Production image: control-plane native binary (Oracle GraalVM 25, Linux) on distroless/base (glibc only, no shell).
#   docker build -f deployment/docker/control-plane.native.Dockerfile -t apigw/control-plane:<version> .
#   --build-arg NATIVE_GC=G1  Oracle GraalVM G1 instead of Serial (ADR 0008)
#   --platform linux/amd64    cross-arch builds run the builder under emulation (slow); prefer a native-arch builder in CI
# Runs as UID 10001, read-only root filesystem, no capabilities (enforced by the Kubernetes manifests and Compose).
# Gradle comes from the official image (same version as gradle/wrapper/gradle-wrapper.properties) so the build does
# not depend on the wrapper's distribution download; dependencies come from Maven Central / the plugin portal.
FROM gradle:9.9.1-jdk25@sha256:9d2e0d1719260ba3d5b15c8d5071b7bc30dbfba6162c18acf620dd48a6c7531a AS gradle
FROM container-registry.oracle.com/graalvm/native-image:25.0.4-ol10 AS build
ARG NATIVE_GC=serial
COPY --from=gradle /opt/gradle /opt/gradle
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    grep -q "gradle-9.9.1-bin.zip" gradle/wrapper/gradle-wrapper.properties \
 && /opt/gradle/bin/gradle --no-daemon --console=plain -Pgw.nativeStaticLibc=true -Pgw.nativeGc=${NATIVE_GC} \
      :control-plane:nativeCompile \
 && cp control-plane/build/native/nativeCompile/control-plane /control-plane

FROM gcr.io/distroless/base-debian13:nonroot@sha256:a08d9054afbaf1af9b90fd02cdc7b32236319b5376d374505a41548d42b0c5db
COPY --from=build --chmod=0555 /control-plane /usr/local/bin/control-plane
USER 10001:10001
EXPOSE 9443 9902
ENTRYPOINT ["/usr/local/bin/control-plane"]
