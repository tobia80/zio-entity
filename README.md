[![CI](https://github.com/thehonesttech/stem/actions/workflows/scala.yml/badge.svg?branch=master)](https://github.com/thehonesttech/stem/actions/workflows/scala.yml) [![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

# ZIO-Entity

Event sourcing refers to a collection of patterns based on persisting the full history of a domain as a sequence of “events”, rather than persisting just the current state.

ZIO-Entity is a ZIO based library that allows to implement distributed event sourcing easily and in a functional way.


## Inspiration
This project is inspired by **Lagom** and **Aecor**.

## Rationale
Historycally all event sourcing frameworks have failed.
The reason, in my opinion is the high barrier to entry. The concept of event sourcing is pretty simple but, applying the concepts
requires a deep understanding of the underlying infrastructure.
Other frameworks had issues with testing, using futures, tests were non deterministic, and flaky.
Stubbing the underlying stores was difficult and sub-optimal.
Other libraries were too difficult to use.

ZIO-Entity wants to be a simple library that brings distributed event sourcing in the ZIO world.

## Features
- Pluggable runtimes. You can distribute your entity calls using Akka-Cluster, Zookeeper, Local, Native,...
- RPC command style invocation.


### ZIO
ZIO-Entity is integrated in the ZIO ecosystem with all the advantages that the effect library can provide. 
As a result, tests can run in ms, they are deterministic, fast and easy to reason.

### RPC style Entities
DDD Entities, use some magic (aka macro) in order to
allow RPC style invocation. Amount of boilerplate code is drastically reduced and an entity can be invoked
as a normal class.
Testing a Stemtity is a lot easier since it can be tested like normal code.

The library will distribute the request in the cluster and serialize commands using either Scodec or Protobuf.

The optional annotation `@MethodId` can be used to maintain schema compatibility if method is renamed.
The id used will be the unique number set in the annotation.

### Pluggable Runtimes

### Replaceable storage

### Testable in milliseconds

