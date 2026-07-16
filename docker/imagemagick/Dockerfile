# =============================================================================
# Docker Hardened Images (DHI) migration -- Debian 13 (trixie)
# =============================================================================
# Real multi-stage build: "-dev" stage (has apt/shell) compiles ImageMagick,
# then its runtime .so dependencies are vendored via `ldd` into
# /opt/imagick/lib/deps so the final stage can be a true minimal DHI runtime
# (no shell, no package manager, non-root by default).
#
# Full rationale, package-name gotchas, and troubleshooting history:
# see docs/technical-guide/developer/dhi-migration-notes.md
#
# Requires: docker login dhi.io (free Docker ID -- DHI is free/Apache 2.0
# since Dec 2025, no paid subscription needed; see the migration notes doc
# for details)
# =============================================================================

# -----------------------------------------------------------------------------
# STAGE 1: build -- DHI "dev" image (has apt, build-essential, shell, root)
# -----------------------------------------------------------------------------
FROM dhi.io/debian-base:trixie-debian13-dev AS build
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    DEBIAN_FRONTEND=noninteractive \
    TZ=Etc/UTC

ARG IMAGEMAGICK_VERSION=7.1.2-24

RUN set -e; \
    apt-get -qq update; \
    apt-get -qq upgrade; \
    apt-get -qqy --no-install-recommends install init-system-helpers; \
    apt-get -qqy --no-install-recommends install \
        autoconf \
        binutils \
        build-essential \
        ca-certificates \
        curl \
        libfftw3-dev \
        libheif-dev \
        libjpeg-dev \
        liblcms2-dev \
        libltdl-dev \
        liblzma-dev \
        libopenexr-dev \
        libxml2-dev \
        libpng-dev \
        librsvg2-dev \
        libtiff-dev \
        libtool \
        libwebp-dev \
        libzip-dev \
        libzstd-dev \
        pkg-config \
    ; \
    curl -LfsSo /tmp/magick.tar.gz https://github.com/ImageMagick/ImageMagick/archive/refs/tags/${IMAGEMAGICK_VERSION}.tar.gz; \
    mkdir -p /tmp/magick; \
    cd /tmp/magick; \
    tar -xf /tmp/magick.tar.gz --strip-components=1; \
    ./configure --prefix=/opt/imagick; \
    make -j 2; \
    make install; \
    rm -rf /opt/imagick/lib/libMagick++*; \
    rm -rf /opt/imagick/include; \
    rm -rf /opt/imagick/share;

# The DHI runtime image ships no apt/dpkg, so `magick`'s ~15 runtime shared
# libs (libheif, libopenjp2, librsvg2, etc.) are vendored here via `ldd` into
# a self-contained bundle instead of relying on system packages at runtime.
RUN set -ex; \
    apt-get -qqy --no-install-recommends install \
        libfontconfig1 \
        libfreetype6 \
        libglib2.0-0 \
        libgomp1 \
        libheif1 \
        libjpeg62-turbo \
        liblcms2-2 \
        libopenexr-3-1-30 \
        libopenjp2-7 \
        libpng16-16 \
        librsvg2-2 \
        libtiff6 \
        libwebp7 \
        libwebpdemux2 \
        libwebpmux3 \
        libxml2 \
        libzip5 \
        libzstd1 \
    ; \
    mkdir -p /opt/imagick/lib/deps; \
    ldd /opt/imagick/bin/magick | awk '{print $3}' | grep '^/' | sort -u \
      | xargs -I{} cp -L --no-clobber {} /opt/imagick/lib/deps/; \
    apt-get -qqy clean; \
    rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# STAGE 2: runtime -- minimal DHI image (no shell, no apt, non-root user 65532)
# -----------------------------------------------------------------------------
FROM dhi.io/debian-base:trixie-debian13 AS image
LABEL maintainer="Penpot <docker@penpot.app>"

ENV LANG='C.UTF-8' \
    LC_ALL='C.UTF-8' \
    TZ=Etc/UTC \
    LD_LIBRARY_PATH=/opt/imagick/lib/deps

COPY --from=build /opt/imagick /opt/imagick

# Runs as the DHI base image's default non-root user (65532) -- no
# useradd/chown needed here, unlike the original Ubuntu-based version.

ENTRYPOINT ["/opt/imagick/bin/magick"]
