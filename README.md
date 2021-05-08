[![CI](https://github.com/thehonesttech/stem/actions/workflows/scala.yml/badge.svg?branch=master)](https://github.com/thehonesttech/zio-entity/actions/workflows/scala.yml) [![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org) 

# ZIO-Entity

Event sourcing refers to a collection of patterns based on persisting the full history of a domain as a sequence of “events”, rather than persisting just the current state.

ZIO-Entity is a ZIO based library that allows to implement distributed event sourcing and CQRS easily and in a functional way.

## Inspiration
This project is inspired by **Lagom** and **Aecor**.

## Rationale
Historycally all event sourcing frameworks have failed.
The reason, in my opinion is the high barrier to entry. The concept of event sourcing is simple enough but, applying the concepts
requires a deep understanding of the underlying infrastructure.

Other frameworks have issues with testing, they use future, tests are non deterministic, and they could be flaky.
Stubbing the underlying stores is difficult and sub-optimal.

These frameworks are pretty opinionated and very soon you can hit limitations.
Other libraries are instead too difficult to use or not maintained anymore.

ZIO-Entity wants to be a simple-to-use library that brings distributed event sourcing in the ZIO world.

## Features
- ZIO
- Easy and versatile API
- RPC style Entities
- ZIO Stream CQRS
- Pluggable runtimes
- Pluggable stores
- Schema evolution
- Testable in milliseconds

### ZIO
ZIO-Entity is integrated in the ZIO ecosystem with all the advantages that the effect library can provide. 
As a result, tests can run in ms, they are deterministic, fast and easy to reason.

### Easy and versatile API
Call an Entity easily like
```scala
accounts(fooAccount)(_.credit(10 EUR))
```

### RPC style Entities
DDD Entities, use some magic (aka macro) in order to
allow RPC style invocation. Amount of boilerplate code is drastically reduced and an entity can be invoked
as a normal class.
Testing a Stemtity is a lot easier since it can be tested like normal code.

The library will distribute the request in the cluster and serialize commands using either Scodec or Protobuf.

The optional annotation `@MethodId` can be used to maintain schema compatibility if method is renamed.
The id used will be the unique number set in the annotation.

### ZIO Stream CQRS
Process ReadSide (CQRS) using ZIO Stream.

### Pluggable Runtimes
You can distribute your entity calls with a pluggable runtime.
At the moment Akka-Cluster, Local and LocalWithProtocol (Test) are ready.
New runtimes using Zookeeper and Native implementations are in the works and new ones can be easily added.

### Pluggable stores
The log and snapshot stores can be configured with implementations available like Memory and Postgres with Cassandra in the works.

### Schema evolution ready
Plug in protobuf, avro, json, zio-schema in order to manage database and communication evolution.

### Testable in milliseconds
Being part of ZIO ecosystem and using ZIO Stream, tests can be easy, quick and deterministic, no more eventually, no more flakyness.
Test tools are available in order to test async interaction in a Reactive way.

### Example
