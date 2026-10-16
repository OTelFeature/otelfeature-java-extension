# syntax=docker/dockerfile:1
#
# The toolchain for otelfeature-java-extension: JDK 21 and Gradle, with the
# build's dependencies already downloaded. `make test` runs the build in it
# locally (nothing but Docker on the host), and CI builds and uses the very
# same image, so the two can't drift.
#
# Stages:
#   toolchain  JDK + Gradle + warmed dependency cache; the repo is mounted at
#              /src at run time (make test / make jar / make shell, and CI).
#   jar        builds the shaded extension JAR from the build context, e.g. a
#              git ref (OTelFeature/poc e2e):
#                docker build --target jar -f ci/Dockerfile \
#                  https://github.com/OTelFeature/otelfeature-java-extension.git#<ref>
#              The JAR is at /out/otelfeature-java-extension.jar.
#   artifact   just the JAR, for `docker build --target artifact --output type=local,dest=<dir> ...`
#              or `COPY --from=` in another Dockerfile.

# Pinned by tag and digest; Dependabot (docker ecosystem) proposes bumps.
FROM gradle:9.8.1-jdk21@sha256:d5c815d1281e3edc7e986ca573b1b309639747fa0d91a2199adadf327cfdf634 AS toolchain

ENV GRADLE_OPTS="-Dorg.gradle.welcome=never"
WORKDIR /src

# Only the build scripts: editing sources doesn't invalidate this layer, and
# warm-cache.gradle resolves every configuration - JARs, not just metadata,
# plugins included - so an --offline build in the image downloads nothing.
#
# The result becomes Gradle's *read-only* dependency cache. make and CI run as
# the host user (so build output in the mounted repo isn't root's), and that
# user can't take Gradle's locks in a root-owned Gradle home - but every user
# can read a read-only cache, with a throwaway writable home of their own.
COPY build.gradle settings.gradle ./
COPY ci/warm-cache.gradle /opt/warm-cache.gradle
RUN GRADLE_USER_HOME=/tmp/warm gradle --init-script /opt/warm-cache.gradle help --no-daemon --quiet \
    && mkdir -p /opt/gradle-ro-cache \
    && mv /tmp/warm/caches/modules-2 /opt/gradle-ro-cache/modules-2 \
    && find /opt/gradle-ro-cache -name '*.lock' -delete \
    && rm -f /opt/gradle-ro-cache/modules-2/gc.properties \
    && chmod -R a+rX /opt/gradle-ro-cache \
    && rm -rf /tmp/warm /src/* /src/.gradle
# Every run gets a fresh, writable Gradle home of its own, whatever its uid:
# Java takes user.home from passwd, not $HOME, and uid 1000 *is* the image's
# `gradle` user (home /home/gradle, a VOLUME), while CI's uid 1001 has no
# entry at all. Set explicitly, local runs and CI take the same path.
ENV GRADLE_RO_DEP_CACHE=/opt/gradle-ro-cache \
    GRADLE_USER_HOME=/tmp/gradle-home

CMD ["gradle", "build", "--no-daemon", "--offline"]

FROM toolchain AS jar
COPY . /src
# shadowJar only: the e2e suite needs the JAR, and the repo's own CI runs the tests.
RUN gradle shadowJar --no-daemon --offline --quiet \
    && mkdir -p /out \
    && cp build/libs/otelfeature-java-extension.jar /out/

FROM scratch AS artifact
COPY --from=jar /out/otelfeature-java-extension.jar /
