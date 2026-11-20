# Upstream content registry baseline

[Issue #55](https://github.com/levkeveli/Min3DStry/issues/55) uses this JVM
reference to verify the order and IDs of content registered by upstream
Mindustry v160.8. Run from a scratch directory with the pinned official
`server-release.jar` described in the [runtime probe](../README.md).

```sh
java -cp /path/to/server-release.jar /path/to/UpstreamContentBaseline.java > registry.log
grep '^UPSTREAM_CONTENT' registry.log > registry.tsv
sha256sum registry.tsv
```

Each record contains the prefix, content type, numeric ID, and internal name,
separated by tabs. The name is `-` when the upstream entry has no mappable name.
The pinned JAR emits 922 records, including 447 blocks, 22 items, 11 liquids,
and 69 units. The SHA-256 of the records with LF line endings and a final
newline is `4bf0187404d8be39036dcda871423de4fee9278e0bae6f220831839496d6895e`.

The probe runs the upstream base registration step before content
initialization. Use its output to check registration order and names. Gameplay
behavior and map loading require separate checks.
