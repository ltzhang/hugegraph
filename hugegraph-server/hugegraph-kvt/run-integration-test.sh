#!/bin/bash

# Build classpath
CP=$(cat classpath.txt)

# Run the integration test with library path
export LD_LIBRARY_PATH=target/native:$LD_LIBRARY_PATH
java -cp target/classes:target/test-classes:$CP \
     -Djava.library.path=target/native \
     HugeGraphKVTIntegrationTest