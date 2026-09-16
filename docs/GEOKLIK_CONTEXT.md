# GeoKlik Android context

Last reviewed against `master` commit `e915792dd88dd560c5b9ae85e4c88b99526a0177` on 2026-09-16.

## Purpose

GeoKlik Android is the offline field-evidence client. It captures camera-originated, location-aware images, stores them locally, and synchronizes eligible evidence to the GeoKlik API.

## Current baseline

- Version code 7; version name 2.4.0
- Java 11; min SDK 24; target/compile SDK 36
- CameraX, WorkManager, Retrofit/OkHttp, HttpURLConnection
- Raw SQLite through `GeoDbHelper`; DB version 119
- ExifInterface, ZXing, osmdroid

## Main code areas

| Concern | Main path |
|---|---|
| Camera/evidence | `presentation/geocamera/GeoCameraActivity.java` |
| Capture preferences/context | `core/utils/CameraPrefs.java`, `data/repository/CaptureContextRepository.java` |
| Local schema | `data/local/db/GeoDbHelper.java` |
| Target sync/storage | `data/remote/ProjectApiService.java`, `data/repository/ProjectRepository.java` |
| Upload queue | `data/sync/UploadWorker.java`, `data/sync/net/ApiService.java` |
| Project selection/QR | `presentation/site/SetSiteActivity.java` |
| Gallery/export | `presentation/gallery/`, `data/export/PhotoExportManager.java` |
| Boundary checks | `ProjectAdminAreaRepository`, `MunicipalityBoundaryRepository`, `BarangayBoundaryRepository` |
| Version policy | `data/remote/AppVersionService.java` |

## Capture modes

| Type | Current behavior |
|---|---|
| `INFRA` | Synced target; administrative-area boundary checks; legacy Infrastructure upload |
| `PROJECT_ACTIVITY` | Synced typed target; dedicated Project Activity upload |
| `PROJECT` / `ACTIVITY` | Recognized and stored as explicit types; server upload routing still requires end-to-end verification |
| `PERSONAL` | Local-only, customizable overlay, status 4, never uploaded |

## Target synchronization

`ProjectApiService` first calls `https://geoklik.philmech.gov.ph/api/capture-targets` and falls back to `/api/projects`. It accepts both `projectId` and legacy `project_id`. The local target model stores type, division, implementors, description, date range, municipality/barangay, and optional legacy radius fields.

Missing or legacy type defaults to `INFRA` for compatibility. `ProjectRepository` preserves explicit `PROJECT`, `ACTIVITY`, and `PROJECT_ACTIVITY` values instead of collapsing them.

## Local database and queue

`GeoDbHelper` version 119 adds typed target metadata, per-photo `monitoring_type`, `activity_project_id`, shot type, capture context, indexes, and migration logic. Existing records without a type default to Infrastructure. Personal captures are converted to local-only status. Project Activity rows are promoted into the normal pending queue now that the API route exists.

`UploadWorker` batches five pending photos. `PROJECT_ACTIVITY` uses `uploadProjectActivityPhoto`; Infrastructure uses the established upload call. Failed items retain local records and error state for retry.

## Infrastructure boundary behavior

Infrastructure targets cache municipality/barangay metadata. Boundary GeoJSON is downloaded from a pinned source, validated, stored in app-private storage, and reused offline. Current municipality validation treats the union of barangay polygons as the allowed area. Project Activity and Personal do not use these restrictions. Exact-radius geofencing is retained only as compatibility metadata and is not the current authorization model.

## Gallery and export behavior

- Gallery shows pending, synced, and failed counts and provides manual `SYNC ALL`.
- App-captured photos can be saved to the device or shared through FileProvider/MediaStore.
- Batch export is duplicate-aware.
- Reassignment flows must preserve capture type and upload identity.
- These features do not authorize importing external images as new evidence.

## Security and current gaps

- `GeoCameraActivity` still contains a hard-coded `QR_HMAC_SECRET` in this public repository. Treat it as compromised and rotate/replace the design.
- The current API does not verify the generated HMAC/signature.
- Production endpoints are embedded in client code.
- The merged API `ProjectSyncService.cs` currently contains merge debris/malformed SQL, so capture-target sync can fail until the server is repaired.
- Standalone `PROJECT` and `ACTIVITY` are recognized by the UI/model, but only `PROJECT_ACTIVITY` has an explicit dedicated upload branch. Verify or define their server contract before rollout.
- Release logs must not expose raw server payloads, precise locations, or beneficiary data.

## Definition of done

A mobile change is complete only when camera-only evidence, metadata, capture-type routing, DB upgrade, offline durability, WorkManager retry/idempotency, server compatibility, boundary behavior, tests/build, versioning, and rollout/rollback are verified.
