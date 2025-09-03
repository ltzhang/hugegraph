# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM openjdk:11-jdk-slim AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    g++ \
    make \
    maven \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /build

# Copy source code
COPY . .

# Build native KVT library
WORKDIR /build/kvt
RUN make clean all

# Build JNI bridge
WORKDIR /build/src/main/native
RUN make clean all

# Build Java module
WORKDIR /build
RUN mvn clean package -DskipTests

# Runtime image
FROM openjdk:11-jre-slim

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    libstdc++6 \
    curl \
    netcat \
    && rm -rf /var/lib/apt/lists/*

# Create user
RUN useradd -m -s /bin/bash hugegraph

# Set up directories
ENV HUGEGRAPH_HOME=/opt/hugegraph-kvt
RUN mkdir -p ${HUGEGRAPH_HOME}/lib/native \
    ${HUGEGRAPH_HOME}/conf \
    ${HUGEGRAPH_HOME}/logs \
    ${HUGEGRAPH_HOME}/data/kvt \
    ${HUGEGRAPH_HOME}/data/kvt/wal \
    ${HUGEGRAPH_HOME}/backup/kvt

# Copy artifacts from builder
COPY --from=builder /build/target/*.jar ${HUGEGRAPH_HOME}/lib/
COPY --from=builder /build/target/native/*.so ${HUGEGRAPH_HOME}/lib/native/
COPY --from=builder /build/kvt/libkvt.so ${HUGEGRAPH_HOME}/lib/native/
COPY --from=builder /build/conf/* ${HUGEGRAPH_HOME}/conf/
COPY --from=builder /build/bin/* ${HUGEGRAPH_HOME}/bin/
COPY --from=builder /build/docs/* ${HUGEGRAPH_HOME}/docs/

# Set permissions
RUN chown -R hugegraph:hugegraph ${HUGEGRAPH_HOME}

# Environment variables
ENV PATH=${HUGEGRAPH_HOME}/bin:${PATH}
ENV LD_LIBRARY_PATH=${HUGEGRAPH_HOME}/lib/native:${LD_LIBRARY_PATH}
ENV JAVA_OPTS="-Djava.library.path=${HUGEGRAPH_HOME}/lib/native"

# Switch to hugegraph user
USER hugegraph
WORKDIR ${HUGEGRAPH_HOME}

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Volumes for data persistence
VOLUME ["${HUGEGRAPH_HOME}/data", "${HUGEGRAPH_HOME}/logs", "${HUGEGRAPH_HOME}/backup"]

# Default configuration
COPY docker/kvt-docker.properties ${HUGEGRAPH_HOME}/conf/kvt.properties

# Startup script
COPY docker/docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh

# Expose ports
EXPOSE 8080 8182

# Entry point
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["server"]