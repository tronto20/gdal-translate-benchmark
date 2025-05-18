ARG GDAL_VERSION=3.10.1

FROM ghcr.io/osgeo/gdal:ubuntu-full-$GDAL_VERSION

ENV LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu/jni:/usr/lib/x86_64-linux-gnu/jni

RUN mkdir /workspace

WORKDIR /workspace
COPY application.jar application.jar

ENTRYPOINT ["java", "-jar", "application.jar"]