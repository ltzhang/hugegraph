# Makefile for KVT JNI library

# Detect OS
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
    OS := linux
    LIBEXT := so
    JNIFLAGS := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux
    LDFLAGS := -shared -fPIC
endif
ifeq ($(UNAME_S),Darwin)
    OS := darwin
    LIBEXT := dylib
    JNIFLAGS := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin
    LDFLAGS := -dynamiclib
endif

# Compiler settings
CXX := g++
CXXFLAGS := -Wall -O2 -fPIC -std=c++17
INCLUDES := $(JNIFLAGS) -I../../../kvt

# Source files
SOURCES := KVTJNIBridge.cpp KVTPushdownFunctions.cpp
OBJECTS := $(SOURCES:.cpp=.o)

# KVT library
KVT_OBJ := ../../../kvt/kvt_mem.o

# Output
OUTDIR := ../../../target/native
TARGET := $(OUTDIR)/libkvtjni.$(LIBEXT)

# Default target
all: $(TARGET)

# Create output directory
$(OUTDIR):
	mkdir -p $(OUTDIR)

# Build the shared library
$(TARGET): $(OUTDIR) $(OBJECTS) $(KVT_OBJ)
	$(CXX) $(LDFLAGS) -o $@ $(OBJECTS) $(KVT_OBJ)
	@echo "Built $(TARGET)"

# Compile source files
%.o: %.cpp
	$(CXX) $(CXXFLAGS) $(INCLUDES) -c $< -o $@

# Clean
clean:
	rm -f $(OBJECTS)
	rm -f $(TARGET)

# Install (copy to system library path)
install: $(TARGET)
	@if [ "$(OS)" = "linux" ]; then \
		sudo cp $(TARGET) /usr/local/lib/; \
		sudo ldconfig; \
		echo "Installed to /usr/local/lib/"; \
	elif [ "$(OS)" = "darwin" ]; then \
		sudo cp $(TARGET) /usr/local/lib/; \
		echo "Installed to /usr/local/lib/"; \
	fi

.PHONY: all clean install