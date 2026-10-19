# Project: Android Mock Location (双模安卓模拟定位)

## Architecture
- **Language**: Kotlin 2.0.21 & Java 17
- **Build System**: Gradle 8.11.1, AGP 8.9.2, compileSdk 35, buildToolsVersion 35.0.0, targetSdk 34, minSdk 26
- **Architecture Pattern**: Clean Architecture + Modular Domain Design (Pure JVM core decoupled from Android SDK Stubs)
- **Layers**:
  1. `com.mockloc.domain.model`: Pure POJO models (GeoPoint, Waypoint, PlannedRoute, GeocodingResult)
  2. `com.mockloc.domain.math`: SpatialMath (Haversine, Bearing, Great Circle Interpolation)
  3. `com.mockloc.domain.engine`: CruiseEngine, MotionSimulator (AR-1 Jitter & OU Micro-Drift)
  4. `com.mockloc.hook`: XposedInit, LocationHook, NetworkAntiDetectHook, MockLocationCleaner
  5. `com.mockloc.root`: RootProviderManager (su shell appops, addTestProvider, provider status)
  6. `com.mockloc.data`: WhitelistRepository, GeocodingRepository (Nominatim + OfflineFallback)
  7. `com.mockloc.presentation`: MainActivity, MapController, WhitelistActivity, SettingsViewModel
  8. `com.mockloc.ipc`: LocationConfigProvider, LocationUpdateReceiver

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Gradle Project & Build Skeleton | Complete Android Gradle structure, wrapper, AGP 8.9.2, Kotlin 2.0.21, local.properties | M1 | AC & R1 |
| 2 | AndroidManifest & Permissions | Manifest with xposedmodule/description/minversion meta-data, location & network permissions, query_all_packages | M1 | AC & R1 |
| 3 | Domain Core Location & Spatial Math | GeoPoint, SpatialMath (Haversine distance, bearing, great circle interpolation) pure JVM POJO | M1 | R4 & AC |
| 4 | Core Hook Engine & Location Interception | LocationManager hooks (getLastKnownLocation, getCurrentLocation, requestLocationUpdates, LocationListener proxy) | M2 | R2 |
| 5 | Anti-Detection & Mock Stripping | Triple sanitization: isFromMockProvider/isMock hook, mFieldsMask reflection, extras cleaning | M2 | R2 & AC |
| 6 | Telephony CellInfo Interception | getAllCellInfo/getCellLocation suppression/mocking to prevent cell location leak | M2 | R3 & AC |
| 7 | WifiManager Scan Interception | getScanResults suppression & WifiInfo BSSID mask (02:00:00:00:00:00) | M2 | R3 & AC |
| 8 | Multi-Process IPC Bridge | ContentProvider & Broadcast for zero-delay cross-process location & setting sync | M2 | R1, R2 |
| 9 | Root Native Mode Provider Engine | Su shell appops grant, addTestProvider, setTestProviderLocation, enable/disable toggle | M3 | R2, R1 |
| 10 | Route Planning & Point Sequence Model | Waypoint, PlannedRoute, sequential point parser and validator | M4 | R4 & AC |
| 11 | Cruise Engine & Step Advance Simulation | Step-wise simulation, distance accumulation, speed presets (walk/bike/drive), loop modes | M4 | R4 & AC |
| 12 | Realistic Motion Simulation | AR-1 speed jitter & Ornstein-Uhlenbeck bounded micro-drift simulation | M4 | R4 & AC |
| 13 | Geocoding & Map Search Model | Search query/result data model, Nominatim REST client + offline landmark fallback | M5 | R1 & AC |
| 14 | Target App Whitelist Selector | Installed app query (PackageManager), filter system apps, multi-select UI & persistence | M5 | R1 |
| 15 | Client Control Center UI & Map Pinning | Map view (OSMDroid), click/long-press coordinate pin, coordinate/speed inputs, Root/Xposed status banner | M5 | R1 |
| 16 | Comprehensive Automated Test Suite | Unit tests for location manipulation, cell/wifi, route interpolation, geocoding models (`./gradlew test`) | M6 | AC |
| 17 | APK Build Verification & Documentation | `./gradlew assembleDebug` verification, comprehensive README.md with deployment guides | M6 | AC |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Gradle Build Skeleton, Manifest & Domain Core Models | Project scaffolding, Gradle wrapper, settings.gradle.kts, build.gradle.kts, local.properties, AndroidManifest.xml, GeoPoint, SpatialMath | none | COMPLETED |
| M2 | Hook Engine, Mock Stripping & Network Anti-Detection | LocationHook, MockLocationCleaner, NetworkAntiDetectHook, LocationConfigProvider, unit tests | M1 | COMPLETED |
| M3 | Root Provider Engine & Environment Detection | RootProviderManager, ShellUtils, RootCheck, unit tests | M1 | COMPLETED |
| M4 | Route Planning, Trajectory Interpolation & Motion Engine | CruiseEngine, MotionSimulator, Waypoint, PlannedRoute, unit tests | M1 | COMPLETED |
| M5 | Control Center UI, Map Search/Pinning & Whitelist Selector | MainActivity, WhitelistActivity, OsmMapController, GeocodingService, layout XMLs | M1, M2, M3, M4 | COMPLETED |
| M6 | Final Verification, E2E Pass & README.md Documentation | Run `./gradlew test` and `./gradlew assembleDebug`, Challenger Stress Tests, Forensic Integrity Audit, complete README.md | M1, M2, M3, M4, M5 | COMPLETED |

## Interface Contracts
### `com.mockloc.domain.math.SpatialMath`
- `calculateHaversineDistance(p1: GeoPoint, p2: GeoPoint): Double`
- `calculateBearing(p1: GeoPoint, p2: GeoPoint): Float`
- `interpolateGreatCircle(p1: GeoPoint, p2: GeoPoint, fraction: Double): GeoPoint`

### `com.mockloc.hook.cleaner.MockLocationCleaner`
- `sanitize(Location, lat: Double, lng: Double, alt: Double, speed: Float, bearing: Float, accuracy: Float)`: strips isFromMockProvider/isMock, clears mFieldsMask (bit 4), scrubs extras.

### `com.mockloc.domain.engine.CruiseEngine`
- `start()`, `pause()`, `resume()`, `reset()`
- `advanceStep(deltaSeconds: Double): CruiseOutput`
- Supports `LoopMode.ONE_WAY`, `LoopMode.PING_PONG`, `LoopMode.LOOP`.

### `com.mockloc.root.RootProviderManager`
- `checkSuAvailable(): Boolean`
- `grantMockPermissionWithSu(packageName: String): Boolean`
- `startMock(...)`, `updateLocation(...)`, `stopMock()`

### `com.mockloc.domain.geocoding.GeocodingService`
- `suspend fun search(query: String, limit: Int = 10): List<GeocodingResult>`
- `suspend fun reverse(latitude: Double, longitude: Double): GeocodingResult?`

## Code Layout
```
/root/模拟定位/
├── local.properties
├── gradle.properties
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── README.md
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   └── xposed_init
        │   ├── java/com/mockloc/
        │   │   ├── domain/
        │   │   │   ├── model/ (GeoPoint, Waypoint, PlannedRoute)
        │   │   │   ├── math/ (SpatialMath)
        │   │   │   ├── engine/ (CruiseEngine, MotionSimulator, CruiseConfig, SpeedPreset)
        │   │   │   └── geocoding/ (GeocodingService, GeocodingResult, OfflineFallbackGeocodingService)
        │   │   ├── hook/
        │   │   │   ├── XposedInit.kt
        │   │   │   ├── LocationHook.kt
        │   │   │   ├── NetworkAntiDetectHook.kt
        │   │   │   └── cleaner/MockLocationCleaner.kt
        │   │   ├── root/
        │   │   │   ├── RootProviderManager.kt
        │   │   │   └── ShellUtil.kt
        │   │   ├── data/
        │   │   │   ├── WhitelistRepository.kt
        │   │   │   └── ConfigManager.kt
        │   │   ├── presentation/
        │   │   │   ├── MainActivity.kt
        │   │   │   ├── WhitelistActivity.kt
        │   │   │   └── map/OsmMapController.kt
        │   │   └── ipc/
        │   │       ├── LocationConfigProvider.kt
        │   │       └── LocationUpdateReceiver.kt
        │   └── res/
        │       ├── layout/
        │       │   ├── activity_main.xml
        │       │   ├── activity_whitelist.xml
        │       │   └── item_app_whitelist.xml
        │       └── values/ (strings, colors, arrays)
        └── test/java/com/mockloc/
            ├── domain/
            │   ├── SpatialMathTest.kt
            │   ├── CruiseEngineTest.kt
            │   ├── MotionSimulatorTest.kt
            │   └── GeocodingModelTest.kt
            ├── hook/
            │   ├── MockLocationCleanerTest.kt
            │   └── NetworkAntiDetectFilterTest.kt
            └── root/
                └── RootProviderCommandTest.kt
```
