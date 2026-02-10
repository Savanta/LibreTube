# Google Cast Integration Notice

## Overview

This fork of LibreTube includes **optional** integration with Google Play Services Cast Framework to enable casting to Smart TV devices (particularly SmartTube on Android TV).

## License Compliance

### LibreTube Core
- **License:** GPL-3.0
- **Source:** https://github.com/libre-tube/LibreTube
- All modifications to LibreTube code remain GPL-3.0 licensed

### Google Play Services Cast Framework
- **Package:** `com.google.android.gms:play-services-cast-framework:21.5.0`
- **License:** Proprietary (Google LLC)
- **Type:** Optional runtime dependency
- **Source:** Not open source

### AndroidX Media3 Cast
- **Package:** `androidx.media3:media3-cast:1.9.1`
- **License:** Apache 2.0
- **Source:** https://github.com/androidx/media

## GPL-3.0 Compliance

This integration complies with GPL-3.0 requirements:

1. ✅ **Application works WITHOUT Google Play Services**
   - Cast functionality is detected at runtime
   - Graceful degradation when Google Play Services is not available
   - Core functionality remains fully operational without Cast

2. ✅ **Source code remains open and GPL-3.0**
   - All Cast integration code is open source
   - No proprietary code is included in this repository
   - Modifications are clearly documented

3. ✅ **Optional dependency**
   - Google Play Services is not required for compilation
   - Users can build and use this app without Google dependencies
   - Cast feature can be disabled in settings

4. ✅ **No license conflicts**
   - GPL-3.0 allows linking with proprietary libraries at runtime
   - Cast SDK is loaded dynamically, not statically linked
   - No GPL code is relicensed or made proprietary

## Technical Implementation

### Runtime Detection
```kotlin
// Cast is initialized safely with try-catch
try {
    CastContext.getSharedInstance(context)
} catch (e: Exception) {
    // Gracefully handles absence of Google Play Services
}
```

### User Control
- Cast can be disabled: Settings → Player → Cast to TV
- Preference key: `cast_enabled` (default: true)
- MediaRouteButton only visible when Cast is available

### Privacy Considerations

While LibreTube focuses on privacy and avoiding Google services, this fork includes Cast as a **convenience feature** for users who:
- Already have Google Play Services installed
- Use SmartTube on their Android TV
- Accept the trade-off for better TV integration

**Users concerned about privacy can:**
- Disable Cast in settings
- Use devices without Google Play Services (app works normally)
- Build the app without Cast dependencies (source available)

## Modifications Made

### New Files
- `app/src/main/java/com/github/libretube/cast/LibreTubeCastOptionsProvider.kt`
- `app/src/main/java/com/github/libretube/cast/CastMediaItemBuilder.kt`
- `app/src/main/java/com/github/libretube/helpers/CastHelper.kt`
- `app/src/main/res/drawable/ic_cast.xml`

### Modified Files
- `app/build.gradle.kts` - Added Cast dependencies
- `gradle/libs.versions.toml` - Added version catalogs
- `app/src/main/AndroidManifest.xml` - Added permissions and CastOptionsProvider
- `app/src/main/java/com/github/libretube/LibreTubeApp.kt` - Initialize Cast
- `app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt` - CastPlayer integration
- `app/src/main/java/com/github/libretube/ui/views/CustomExoPlayerView.kt` - MediaRouteButton
- `app/src/main/res/layout/exo_styled_player_control_view.xml` - Cast button UI
- `app/src/main/res/values/strings.xml` - Cast strings
- `app/src/main/res/xml/player_settings.xml` - Cast preference
- `app/src/main/java/com/github/libretube/constants/PreferenceKeys.kt` - Cast preference key

### Dependencies Added
```kotlin
implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
implementation("androidx.media3:media3-cast:1.9.1")
implementation("androidx.mediarouter:mediarouter:1.7.0")
```

## Disclaimer

This is a **personal fork** for private use. The Cast integration:
- Is NOT part of upstream LibreTube
- Is NOT intended for F-Droid distribution (Google Play Services dependency)
- Is specifically for users who want SmartTube integration
- Maintains GPL-3.0 licensing for all custom code

## Contact

For questions about this fork:
- GitHub: https://github.com/Savanta/LibreTube
- Upstream LibreTube: https://github.com/libre-tube/LibreTube

---

**Last Updated:** February 2026
**Fork Maintainer:** Savanta
**Upstream License:** GPL-3.0
**Cast Integration:** Optional proprietary runtime dependency
