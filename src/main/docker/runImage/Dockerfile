ARG GDAL_VERSION=3.10.1

FROM ghcr.io/osgeo/gdal:ubuntu-full-$GDAL_VERSION

ENV LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/usr/lib/aarch64-linux-gnu/jni:/usr/lib/x86_64-linux-gnu/jni

ARG STACK_ID=io.buildpacks.stacks.jammy.tiny
LABEL io.buildpacks.stack.id=$STACK_ID
