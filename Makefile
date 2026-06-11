# Convenience wrapper around the Gradle wrapper for common operations.
# Windows users without `make` can run the underlying ./gradlew commands directly
# (shown in each target). All targets use the project's pinned toolchain.

GRADLEW ?= ./gradlew

.PHONY: help build test bench run clean check graph

help:           ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  %-10s %s\n", $$1, $$2}'

build:          ## Compile and assemble everything
	$(GRADLEW) build

test:           ## Run all unit tests
	$(GRADLEW) test

check:          ## Run the full verification pipeline (tests + checks)
	$(GRADLEW) check

bench:          ## Run the JMH benchmarks (smoke profile)
	$(GRADLEW) :jediscore-benchmarks:jmh

run:            ## Run the server application
	$(GRADLEW) :jediscore-server:run

graph:          ## Print the module list
	$(GRADLEW) moduleGraph -q

clean:          ## Delete all build outputs
	$(GRADLEW) clean
