# GeoKlik Camera Module Refactor

Base: `feature/gallery-photo-actions` (GeoKlik 2.4.0)

## Goal
Reduce `GeoCameraActivity` to orchestration/UI only without changing field behavior, database schema, API contracts, capture rules, watermark format, or synchronization behavior.

## Target structure

```text
GeoCameraActivity
  -> CameraController
  -> LocationController
  -> CaptureValidator
  -> WatermarkProcessor
  -> CaptureRepository
```

## Responsibilities

### GeoCameraActivity
- Android lifecycle and permission result wiring
- View binding and dialogs
- Coordinates controllers
- No low-level CameraX, GNSS, watermark bitmap, EXIF, or SQL implementation

### CameraController
- ProcessCameraProvider lifecycle
- Preview + ImageCapture shared ViewPort / UseCaseGroup
- main/ultrawide capability detection and lens switching
- target rotation
- CameraGestureController attachment
- capture use-case access through a narrow callback/API

### LocationController
- GPS + fused location pipelines
- GNSS satellite status
- stale-location handling
- GPS-only vs Indoor Assist selection
- mock-location filtering
- exposes immutable location state to the Activity/validator

### CaptureValidator
- pure capture eligibility rules
- GPS accuracy/freshness/satellite/stability checks
- required site/project checks
- duplicate-distance policy: Infrastructure only
- no Activity/View/SQLite dependencies where practical

### WatermarkProcessor
- bitmap decode/render/re-encode
- QR + barcode generation
- EXIF preservation and GPS metadata
- signature metadata generation interface
- returns success/failure; does not update UI or DB

### CaptureRepository
- capture persistence boundary around ImageMetaRepository / project/site lookups
- creates/updates local capture metadata
- duplicate queries
- upload scheduling trigger after successful local persistence
- keeps Retrofit/WorkManager implementation out of GeoCameraActivity

## Non-negotiable compatibility rules
1. Default camera remains 1x.
2. Ultrawide is used only when the device exposes a usable rear ultrawide and user pinches out.
3. Preview and ImageCapture stay in the same UseCaseGroup/ViewPort.
4. Infrastructure retains the 5 m duplicate warning; Personal Capture and Project Activity do not.
5. Discard returns capture state immediately to WAITING_FOR_GPS and re-evaluates eligibility.
6. Existing DB schema and server upload contracts remain unchanged.
7. Existing watermark/QR/EXIF output remains byte-format compatible where practical.
8. Offline-first behavior and WorkManager sync behavior remain unchanged.

## Staged migration

### Phase 1 - CaptureValidator
Extract pure rules first and add unit tests. Lowest Android coupling.

### Phase 2 - CameraController
Move CameraX provider, binding, rotation and ultrawide selection. Keep callbacks into Activity.

### Phase 3 - LocationController
Move GNSS/Fused providers and expose location state callbacks.

### Phase 4 - WatermarkProcessor
Move CPU/file-heavy image processing and EXIF functions without changing output format.

### Phase 5 - CaptureRepository
Move DB/persistence and post-save sync trigger.

### Phase 6 - Activity cleanup
GeoCameraActivity becomes lifecycle/UI orchestration only.

## Merge gate
The refactor branch must compile and pass manual regression testing for capture/save/discard, GPS gating, all three documentation modes, main/wide lens switching, offline capture, gallery visibility, and upload retry before it is merged back into `feature/gallery-photo-actions`.
