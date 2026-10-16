# Build and test in the toolchain image (ci/Dockerfile). The only host
# requirement is Docker; CI runs the same image and the same commands.

IMAGE ?= otelfeature-java-extension-ci
DOCKER ?= docker

# Run as the host user, so build output in the mounted repo isn't root's.
# Rootless Docker/Podman already map container root to the host user: use
# DOCKER_USER=0:0 there. HOME points somewhere writable for any uid.
DOCKER_USER ?= $(shell id -u):$(shell id -g)

# An interactive terminal only when there is one on both ends (not in CI,
# not when piped into tee). Checked by the recipe's own shell: inside
# $(shell ...) stdout is a pipe, so the test there would always say no.
TTY = $$([ -t 0 ] && [ -t 1 ] && echo -it)

DOCKER_RUN = $(DOCKER) run --rm --user "$(DOCKER_USER)" -e HOME=/tmp \
	-v "$(CURDIR)":/src -w /src

RUN_IN_IMAGE = $(DOCKER_RUN) $(TTY) $(IMAGE)

.PHONY: image test jar shell clean

## image: build the toolchain image (cached; only build.gradle/settings.gradle changes rebuild the dependency layer)
image:
	$(DOCKER) build --target toolchain -t $(IMAGE) -f ci/Dockerfile .

# --offline: the image carries every dependency. A build.gradle change rebuilds
# the image's dependency layer first (the image target), so this never goes stale.
GRADLE = gradle --no-daemon --offline

## test: compile, test and package - the same as CI
test: image
	$(RUN_IN_IMAGE) $(GRADLE) build

## jar: build build/libs/otelfeature-java-extension.jar without running the tests
jar: image
	$(RUN_IN_IMAGE) $(GRADLE) shadowJar

## shell: a shell in the toolchain image, with the repo mounted at /src
shell: image
	$(DOCKER_RUN) -it $(IMAGE) bash

## clean: remove build output (it is the host user's; no image needed)
clean:
	rm -rf build .gradle
