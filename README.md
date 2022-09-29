[![](https://jitpack.io/v/caesarsdigital/trace4cats-zio-extras.svg)](https://jitpack.io/#caesarsdigital/trace4cats-zio-extras)

trace4cats-zio-extras
---------------------

ZIO-specific extensions to the trace4cats library.

# Modules

## core

Defines the core constructs (ZTracer) that are used by the other modules.  This module depends solely on trace4cats + zio.

## sttp

Provides a tracing enriched STTP backend (HTTP client) utilizing the constructs defined in trace4cats-zio-extras-core.

## zhttp

Provides a tracing enriched zio-http server (HTTP server) utilizing the constructs defined in trace4cats-zio-extras-core.

## tests

Defines an externalized test suite, which is required in order to test the components in unison.

Provides helpers to faciliate testing where a `ZTracer` capability is required in the zio environment.

# Usage

Add the following to your `build.sbt`:

```scala
libraryDependencies += "com.github.caesarsdigital.trace4cats-zio-extras" %% "trace4cats-zio-extras-${MODULE NAME}" % "${VERSION}"
```
