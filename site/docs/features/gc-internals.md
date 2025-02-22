# Nessie GC Internals

_aka Nessie-aware delete-orphan-files_

Consists of a `gc-base` module, which contains the general base functionality to access a
repository to identify the live contents, to identify the live files, to list the existing files
and to purge orphan files.

Modules that supplement the `gc-base` module:

* `gc-iceberg` implements the Iceberg table-format specific functionality.
* `gc-iceberg-files` implements file listing + deletion using Iceberg's `FileIO`.
* `gc-iceberg-mock` is a testing-only module to generate mock metadata, manifest-lists, manifests
  and (empty) data files.
* `gc-repository-jdbc` implements the live-content-sets-store using JDBC (PostgreSQL and compatible)
  .
* `s3mock` is a testing-only module containing a S3 mock backend that allows listing objects and
  getting objects programmatically.
* `s3mino` is a junit 5 test extension providing a Minio based S3 backend.

The `gc-tool` module is a command-line interface, a standalone tool provided as an executable,
it is an uber jar prefixed with a shell script, and can still be executed with `java -jar ...`.

## Basic Nessie-GC functionality

Nessie-GC implements a mark-and-sweep approach, a two-phase process:

The "mark phase", or "live content identification", walks all named references and collects
references to all `Content` objects that are considered as live. Those references are stored in a
repository as a "live contents set". The "mark phase" is implemented in `IdentifyLiveContents`.

The "sweep phase", or "delete orphan files", operates per content-id. For each content, all live
versions of a `Content` are scanned to identify the set of live data files. After that, the
base-location(s)  are scanned and all files that are not in the set of live data files are deleted.
The "sweep phase" is implemented by `DefaultLocalExpire`.

## Inner workings

To minimize the amount of data needed to match against the set of live data files for a `Content`,
the implementation does not actually remember all individual data files, like maintaining a
`java.util.Set` of all those data files, but remembers all data files in a bloom filter.

Both the "mark" (identify live contents) and "sweep" (identify and delete expired contents) phases
provide a configurable _parallelism_: the number of concurrently scanned named references can be
configured and the amount of concurrently processed tables can be configured.

### _Mark_ phase optimization

The implementation that walks the commit logs can be configured with a `VisitedDeduplicator`, which
is meant to reduce the work required during the "mark" phase, if the commit to be examined has
already been processed.

There is a `DefaultVisitedDeduplicator` implementation, but it is likely that it requires too much
memory during runtime, especially when the identify-run is configured with multiple GC policies
and/or has to walk many commits. This `DefaultVisitedDeduplicator` is present, but due to the
mentioned concerns _not_ available in the Nessie GC tool and the use of
`DefaultVisitedDeduplicator` is not supported at all, and not recommended.

## Identified live contents repository

It is recommended to use an external database for the Nessie GC repository. This is especially
recommended for big Nessie repositories.

Nessie GC runs against small-ish repositories do technically work with an in-memory repository.
But, as the term "in memory" suggests, the identified live-contents-set, its state, duration, etc.
cannot be inspected afterwards.

## Pluggable code

Different parts / functionalities are quite isolated and abstracted to allow proper
unit-testability and also allow reuse of similar functionality.

Examples of abstracted/isolated functionality:

* Functionality to recursively walk a base location
* Functionality to delete files
* Nessie GC repository
* Getting all data files for a specific content reference (think: Iceberg table snapshot)
* Commit-log-scanning duplicate work elimination

## File references

All files (or objects, in case of an object store like S3) are described using a `FileReference`,
using a _base_ URI plus a URI _relative_ to the base URI. Noteworthy: the "sweep phase", which
"collects" all live files in a bloom filter and after that lists files in all _base_ URIs, always
uses only the _relative_ URI, never the _full_ URI, to check whether a file is orphan or probably
not (bloom filter is probabilistic data structure).

Since object stores are the primary target, only files but not directories are supported. Object
stores do not know about directories, further Iceberg's `FileIO` does not know about directories
either. For file systems that do support directories this means, that empty directories will not be
deleted, and prematurely deleting directories could break concurrent operations.

## Runtime requirements

Nessie GC work is dominated by network and/or disk I/O, less by CPU and heap pressure.

Memory requirements (rough estimates):

* Number of concurrent content-scans ("sweep phase") times the bloom-filter on-heap size
  (assume that can be a couple MB, depending on the expected number of files and allowed
  false-positive ratio).
* Duplicate-commit-log-walk elimination requires some amount of memory for each distinct cut-off
  time times the (possible) number of commits over the matching references.
* Additional memory is required for the currently processed chunks of metadata, for example
  Iceberg's table-metadata and manifests, to identify the live data files. (The raw metadata is
  only read and processed, but not memoized.)
* An in-memory live-contents-repository (**not recommended for production workloads**) requires
  memory for all content-references.

### CPU & heap pressure testing

Special "tests" ([this](https://github.com/projectnessie/nessie/blob/main/gc/gc-base/src/test/java/org/projectnessie/gc/huge/TestManyObjects.java) and
([this](https://github.com/projectnessie/nessie/blob/main/gc/gc-iceberg-inttest/src/test/java/org/projectnessie/gc/iceberg/inttest/ITHuge.java)) have
been used to verify that even a huge amount of objects does not let a tiny Java heap "explode" and
not use excessive CPU resources. This "test" simulates a Nessie repository with many references,
commits, contents and files per content version. Runs of that test using a profiler proved that the
implementation requires little memory and little CPU - runtime is largely dominated by bloom-filter
_put_ and _maybe-contains_ operations for the per-content-expire runs. Both tests proved the
concept.

## Deferred deletion

The default behavior is to immediately deletes orphan files. But it is also possible to record the
files to be deleted and delete those later. The `nessie-gc` tool supports deferred deletion.

## Non-Nessie use cases

Although all the above is designed for Nessie, it is possible to reuse the core implementation with
"plain" Iceberg, effectively a complete replacement of Iceberg's _expire snapshots_ and _delete
orphan files_, but without Iceberg's implicit requirement of using Spark. Things needed for this:

* A "pure Iceberg" implementation of `org.projectnessie.gc.repository.RepositoryConnector`:
  * Return one reference per Iceberg table, derived from the underlying Iceberg catalog.
  * Provide a commit log with one `Put` operation for each Iceberg snapshot.
  * (The `allContents` function can return an empty `Stream `for the "pure Iceberg" use case.)
* Existing functionality, the mark-and-sweep logic and the code in `nessie-gc-iceberg` and
  `nessie-gc-iceberg-files`, can be reused without any changes.

## Potential future enhancements

Since Nessie GC keeps track of all ever live content-references and all ever known base content
locations, it is possible to identify ...

* ... the base content locations that are no longer used. In other words: storage of e.g. Iceberg
  tables that have been deleted and are no longer referenced in any live Nessie commit.
* ... the content references (aka Iceberg snapshots) are no longer used. This information can be
  used to no longer expose the affected e.g. Iceberg snapshots in any table metadata.

### Completely unreferenced contents

Files of contents that are not visible from any live Nessie commit can be completely removed.
Detecting this situation is not _directly_ supported by the above approach.

The live-contents-set generated by Nessie GC's identify phase contains all content IDs that are
"live". Nessie (server) _could_ (and this approach is really just a thought) help here, by sending
the "live" content IDs to Nessie and Nessie returning one content object for all content IDs that
are not contained in the set of "live" content IDs. Another implementation would then be
responsible to inspect the returned contents and purge the base locations in the data lake, where
the data files, manifests, etc were stored.

The above must not purge files for content IDs that have just been recently created.

## Potential Iceberg specific enhancements

Nessie GC can easily identify the Iceberg snapshots, as each Nessie commit references exactly one
Iceberg table snapshot. Nessie (the runtime/server) has no knowledge of whether a particular
Iceberg partition-spec, sort-order or schema is used via any live Iceberg snapshot or not, because
partition-specs, sort-orders and schemas are referenced via Iceberg manifests. Although Nessie GC
can identify these three kinds of structures during the identification of live contents, the expire
phase, that maps content references to files, does not respect Nessie commit order.

So it is necessary to fully think through a potential "expire specs/sort-orders/schemas", keeping
in mind:

* Commit-chains are important, because IDs of partition-specs, sort-orders and schemas are assigned
  sequentially (a 32 bit int).
* The logically same partition-specs, sort-orders and schemas may exist with different IDs on
  different Nessie references.
* Partition-specs, sort-orders and schemas are only maintained in table-metadata, but referenced
  from "deeper" structures (manifest list, manifest files, data files).
* Is it really worth to have an "expire specs/sort-orders/schemas".
