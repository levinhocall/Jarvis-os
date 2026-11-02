# Room schema snapshots

Room schema export is enabled in `MemoryDatabase` and KAPT writes versioned schema JSON files to this directory.

The currently committed database version is **5**. The next successful Room compilation should generate the version-5 snapshot. Historical version snapshots are not reconstructed here because they must match the exact schema that shipped in each prior version; migration coverage remains an explicit verification task.
