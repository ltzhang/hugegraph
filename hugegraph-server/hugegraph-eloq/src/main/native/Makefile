# Makefile for EloqRocks JNI library
#
# This builds libeloqjni.so which bridges Java ↔ EloqRocks C++.
#
# Usage:
#   make clean all \
#     OUTDIR=../../../target/native \
#     ELOQROCKS_BLD=../../eloqrocks/bld \
#     ELOQROCKS_SRC=../../eloqrocks
#
# Prerequisites:
#   - EloqRocks must be built first (cmake in eloqrocks/bld/)
#   - JAVA_HOME must point to a JDK with include/jni.h

# Detect OS
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
    OS := linux
    LIBEXT := so
    JNIFLAGS := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux
    LDFLAGS_OS := -shared -fPIC
endif
ifeq ($(UNAME_S),Darwin)
    OS := darwin
    LIBEXT := dylib
    JNIFLAGS := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin
    LDFLAGS_OS := -dynamiclib
endif

# Compiler settings
CXX := g++
CXXFLAGS := -Wall -O2 -fPIC -std=c++17 \
    -DELOQ_MODULE_ELOQROCKS -DELOQ_MODULE_ENABLED \
    -DEXT_TX_PROC_ENABLED -DOPEN_LOG_SERVICE=1 \
    -DWITH_LOG_SERVICE=1

# EloqRocks source and build directories (passed from Maven or set manually)
ELOQROCKS_SRC ?= ../../../eloqrocks
ELOQROCKS_BLD ?= ../../../eloqrocks/bld

# Include paths:
# - JNI headers
# - EloqRocks public headers
# - data_substrate headers (tx_service, core, store_handler)
# Match the exact include paths from CMake (flags.make) + system headers
INCLUDES := $(JNIFLAGS) \
    -I$(ELOQROCKS_SRC)/include \
    -I$(ELOQROCKS_SRC)/data_substrate \
    -I$(ELOQROCKS_SRC)/data_substrate/core/include \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/include \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/include/cc \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/include/remote \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/include/fault \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/include/proto \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/tx-log-protos \
    -I$(ELOQROCKS_SRC)/data_substrate/tx_service/abseil-cpp \
    -I$(ELOQROCKS_SRC)/data_substrate/store_handler \
    -I$(ELOQROCKS_SRC)/data_substrate/eloq_metrics/include \
    -I$(ELOQROCKS_SRC)/data_substrate/log_service/include \
    -I/usr/local/include/mimalloc-2.1

# Source files
SOURCES := EloqJNIBridge.cpp
OBJECTS := $(SOURCES:.cpp=.o)

# ============================================================================
# Libraries to link (matches the CMake link order from eloqrocks executable)
# ============================================================================

ELOQROCKS_LIB := $(ELOQROCKS_BLD)/libeloqrocks_lib.a
DS := $(ELOQROCKS_BLD)/data_substrate
ABSL := $(DS)/tx_service/abseil-cpp/absl

# Static libraries from the EloqRocks CMake build
STATIC_LIBS := \
    $(ELOQROCKS_LIB) \
    $(DS)/libdata_substrate.a \
    $(DS)/libtxservice.a \
    $(DS)/libeloq-metrics.a \
    $(DS)/liblogservice.a

# Abseil static libraries (subset actually needed by data_substrate)
ABSL_LIBS := \
    $(ABSL)/strings/libabsl_cord.a \
    $(ABSL)/strings/libabsl_cordz_info.a \
    $(ABSL)/strings/libabsl_cord_internal.a \
    $(ABSL)/strings/libabsl_cordz_functions.a \
    $(ABSL)/strings/libabsl_cordz_handle.a \
    $(ABSL)/crc/libabsl_crc_cord_state.a \
    $(ABSL)/crc/libabsl_crc32c.a \
    $(ABSL)/crc/libabsl_crc_internal.a \
    $(ABSL)/crc/libabsl_crc_cpu_detect.a \
    $(ABSL)/strings/libabsl_str_format_internal.a \
    $(ABSL)/container/libabsl_raw_hash_set.a \
    $(ABSL)/hash/libabsl_hash.a \
    $(ABSL)/types/libabsl_bad_optional_access.a \
    $(ABSL)/hash/libabsl_city.a \
    $(ABSL)/types/libabsl_bad_variant_access.a \
    $(ABSL)/hash/libabsl_low_level_hash.a \
    $(ABSL)/container/libabsl_hashtablez_sampler.a \
    $(ABSL)/profiling/libabsl_exponential_biased.a \
    $(ABSL)/synchronization/libabsl_synchronization.a \
    $(ABSL)/debugging/libabsl_stacktrace.a \
    $(ABSL)/synchronization/libabsl_graphcycles_internal.a \
    $(ABSL)/synchronization/libabsl_kernel_timeout_internal.a \
    $(ABSL)/time/libabsl_time.a \
    $(ABSL)/time/libabsl_civil_time.a \
    $(ABSL)/time/libabsl_time_zone.a \
    $(ABSL)/debugging/libabsl_symbolize.a \
    $(ABSL)/strings/libabsl_strings.a \
    $(ABSL)/strings/libabsl_strings_internal.a \
    $(ABSL)/strings/libabsl_string_view.a \
    $(ABSL)/numeric/libabsl_int128.a \
    $(ABSL)/debugging/libabsl_debugging_internal.a \
    $(ABSL)/base/libabsl_malloc_internal.a \
    $(ABSL)/debugging/libabsl_demangle_internal.a \
    $(ABSL)/base/libabsl_base.a \
    $(ABSL)/base/libabsl_spinlock_wait.a \
    $(ABSL)/base/libabsl_throw_delegate.a \
    $(ABSL)/base/libabsl_raw_logging_internal.a \
    $(ABSL)/base/libabsl_log_severity.a

# Shared system libraries
# NOTE: mimalloc must be preloaded via LD_PRELOAD when running inside a JVM
# to avoid allocator conflicts. See pom.xml surefire config.
SYSTEM_LIBS := -lbrpc -lbraft -lgflags -lglog -lprotobuf \
    -lrocksdb -lleveldb -lmimalloc \
    -lprometheus-cpp-pull -lprometheus-cpp-core \
    -lpthread -ldl -lrt -lz

# Output
OUTDIR ?= ../../../target/native
TARGET := $(OUTDIR)/libeloqjni.$(LIBEXT)

# Default target
all: $(TARGET)

# Create output directory
$(OUTDIR):
	mkdir -p $(OUTDIR)

# Build the shared library
$(TARGET): $(OUTDIR) $(OBJECTS)
	$(CXX) $(LDFLAGS_OS) -o $@ $(OBJECTS) \
		-Wl,--whole-archive $(ELOQROCKS_LIB) -Wl,--no-whole-archive \
		$(filter-out $(ELOQROCKS_LIB),$(STATIC_LIBS)) \
		$(ABSL_LIBS) \
		$(SYSTEM_LIBS) \
		-Wl,-rpath,/usr/local/lib
	@echo "Built $(TARGET)"

# Compile source files
%.o: %.cpp
	$(CXX) $(CXXFLAGS) $(INCLUDES) -c $< -o $@

# Clean
clean:
	rm -f $(OBJECTS)
	rm -f $(TARGET)

.PHONY: all clean
