# GeoKlik Android agent instructions

## Required context

Before changing code, read `docs/GEOKLIK_CONTEXT.md`. Inspect the real Java, XML, Gradle, SQLite, worker, and API paths involved.

## Current baseline

- Java 11, application ID `ph.gov.geocamera`
- Minimum SDK 24; target/compile SDK 36
- Current release metadata: version code 7, version name 2.4.0
- CameraX, WorkManager, Retrofit/OkHttp, HttpURLConnection, SQLiteOpenHelper, ExifInterface, ZXing, and osmdroid
- Local database version 119

## Evidence invariants

- New field evidence must come from the in-app camera. Never add gallery/file-picker evidence import.
- QR images may be selected only to decode a project code; they are not evidence.
- Preserve GPS/accuracy, timestamp, UUID, EXIF, watermark, QR, and signature/HMAC metadata.
- Preserve offline durability, stable identity, retry, and no-loss behavior.
- Never delete a capture only because upload failed.

## Capture-type rules

- Keep `INFRA`, `PROJECT`, `ACTIVITY`, `PROJECT_ACTIVITY`, and `PERSONAL` explicit.
- The primary sync endpoint is `/api/capture-targets`; `/api/projects` is the Infrastructure fallback.
- Only `PROJECT_ACTIVITY` currently uses `/api/project-activity/upload`; Infrastructure uses `/api/geocamera/upload`.
- `PERSONAL` is local-only (`status=4`) and must not enter server upload work.
- Verify routing for standalone `PROJECT` and `ACTIVITY`; do not silently send a type through the wrong contract.
- Infrastructure capture uses synchronized administrative-area metadata and cached municipal/barangay boundaries. Project Activity and Personal are not boundary-restricted.

## Gallery and local-data rules

- Gallery screens review app-captured records and may save/export/share copies; these actions must not mutate source evidence or queue identity.
- Photo reassignment must preserve type compatibility, metadata, and upload state.
- SQLite changes require additive migration/onUpgrade coverage from older installed versions.
- WorkManager retries must remain idempotent and must not strand status 1/2/3 records.

## Security and privacy

- Never commit secrets, signing keys, credentials, beneficiary images, or production data.
- The hard-coded `QR_HMAC_SECRET` is compromised by publication; do not treat it as a production trust anchor.
- Do not weaken TLS, location/boundary checks, camera-only capture, or release logging safeguards.
- Changes to app ID, signing, version, permissions, storage, or production URLs require explicit approval.

## Development workflow

1. Trace UI → preferences/context → repository → SQLite → worker → API.
2. Confirm capture type and identity at every handoff.
3. Test upgrade from an older DB, offline capture, process restart, queueing, retry, duplicate prevention, and server rejection.
4. Test INFRA, PROJECT_ACTIVITY, and PERSONAL independently.
5. Run `gradlew.bat test` or `./gradlew test`, then an appropriate debug build.
6. Report API dependencies, migrations, versioning, tests, deployment needs, and risks.
