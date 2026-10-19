# E2E Test Infra: Android Mock Location

## Test Philosophy
- Requirement-driven, opaque-box & automated JVM test architecture.
- Methodology: Category-Partition + Boundary Value Analysis (BVA) + Pairwise Interaction Testing + Real-World Workload Scenarios.
- Decoupled testing: Pure Kotlin core runs seamlessly on host JVM via `./gradlew test` with `unitTests.isReturnDefaultValues = true`.

## Feature Inventory Test Mapping
| # | Feature | Requirement Source | Tier 1 | Tier 2 | Tier 3 | Tier 4 |
|---|---------|-------------------|:------:|:------:|:------:|:------:|
| 1 | Location Mock & Flag Stripping | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| 2 | Telephony CellInfo Interception | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| 3 | WifiManager Scan Interception | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| 4 | Spatial Math & Haversine Distance | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |
| 5 | Trajectory Cruise & Interpolation | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |
| 6 | Motion Jitter & Micro-Drift Simulation | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |
| 7 | Geocoding & Location Search | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| 8 | Root Provider Commands & AppOps | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |

## Test Architecture
- **Test Runner**: `./gradlew test` (and `./gradlew testDebugUnitTest`)
- **Pass/Fail Semantics**: 0 failures, 0 errors, exit code 0.
- **Packaging Verification**: `./gradlew assembleDebug` must produce `app-debug.apk` without compilation errors.
- **Integrity Validation**: Zero hardcoded mocks, genuine mathematical and reflection logic.

## Test Tier Structure
1. **Tier 1 - Feature Coverage**:
   - Location manipulation: Coordinates (lat, lng, alt, accuracy, speed, bearing, timestamp) set accurately.
   - Mock stripping: `isFromMockProvider()` returns false; `isMock()` returns false; `FLAG_MOCKED` cleared; extras sanitized.
   - CellInfo interception: Returns empty list or mocked cells; physical tower leakage blocked.
   - WiFi interception: Returns empty scan list; BSSID sanitized to "02:00:00:00:00:00".
   - Spatial math: Haversine distance, azimuth/bearing calculation, great circle interpolation.
   - Cruise engine: start/pause/resume/reset transitions.
   - Motion simulation: Speed jitter generation, bounded drift application.
   - Geocoding: Search query mapping and offline landmark fallback.
2. **Tier 2 - Boundary & Corner Cases**:
   - Extreme coordinates: Equator (0,0), Poles (+90, -90), Date line (+180, -180).
   - Speed boundaries: 0 speed, max speed (100 m/s), negative values handling.
   - Empty/Single point route handling.
   - Drift bounding: 1000 iterations verifying maximum drift does not exceed 4.0 meters.
   - Null and malformed package name handling in whitelist.
3. **Tier 3 - Cross-Feature Interactions**:
   - Trajectory cruise combined with speed jitter and micro-drift simulation.
   - Simultaneous Mock Location + CellInfo block + WiFi block payload consistency.
   - Whitelist toggle switching between Hook mode and bypass mode.
4. **Tier 4 - Real-World Application Scenarios**:
   - Scenario 1: Beijing to Shanghai High-Speed / Driving Cruise simulation.
   - Scenario 2: Commuter Walking Simulation (1.4 m/s + drift) with continuous location updates.
   - Scenario 3: Stationary Office Attendance Punch-in (sub-meter drift simulating satellite noise).
   - Scenario 4: Anti-Cheat Inspection Simulation (asserting neither `isFromMockProvider` nor physical BSSID leaks).
