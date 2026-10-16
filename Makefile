# Build and test in the toolchain image (ci/Dockerfile). The only host
# requirement is Docker; CI runs the same image and the same commands.

IMAGE ?= otelfeature-java-extension-ci
DOCKER ?= docker

# Run as the host user, so build output in the mounted repo isn't root's.
# HOME points somewhere writable for that user, who has no home in the image.
RUN_IN_IMAGE = $(DOCKER) run --rm $(TTY) \
	--user "$$(id -u):$$(id -g)" -e HOME=/tmp \
	-v "$(CURDIR)":/src -w /src \
	$(IMAGE)

# An interactive terminal only when there is one (not in CI).
TTY := $(shell [ -t 0 ] && echo -it)

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
	$(DOCKER) run --rm -it --user "$$(id -u):$$(id -g)" -e HOME=/tmp -v "$(CURDIR)":/src -w /src $(IMAGE) bash

## clean: remove build output
clean: image
	$(RUN_IN_IMAGE) $(GRADLE) clean
