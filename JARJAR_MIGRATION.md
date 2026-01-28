# Migration from ShadowJar to jarJar

## Summary

This mod has been migrated from using **ShadowJar** to **jarJar** for dependency bundling. This change resolves classloading and mapping issues with Forge 1.20.1.

## What Changed?

### Dependencies (build.gradle)

All `shadow '...'` declarations have been replaced with `jarJar '...'`:

**Before:**
```gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
shadow 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

**After:**
```gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
jarJar 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

### Build Configuration

The `shadowJar { ... }` configuration block has been disabled/commented out. The Shadow plugin remains in the plugins block but is no longer used.

## Why jarJar Instead of ShadowJar?

1. **Forge Integration**: jarJar is Forge's native dependency bundling mechanism and integrates seamlessly with ForgeGradle and the reobfJar process.

2. **Proper Reobfuscation**: jarJar-bundled dependencies are properly reobfuscated, preventing classloading issues at runtime.

3. **Jar-in-Jar Format**: Dependencies are embedded as separate JARs in `META-INF/jarjar/` rather than being merged into a single fat JAR. This is the format Forge's ModLauncher expects.

4. **No Relocation Needed**: With jarJar, you don't need package relocation since dependencies are isolated as separate JARs.

## How to Build

```bash
./gradlew clean build
```

The resulting JAR will be in `build/libs/steve-ai-mod-1.0.0.jar`.

## How to Verify

1. **Check for jarJar entries:**
```bash
jar tf build/libs/steve-ai-mod-1.0.0.jar | grep -i jarjar
```

You should see entries like:
```
META-INF/jarjar/
META-INF/jarjar/caffeine-3.1.8.jar
META-INF/jarjar/resilience4j-circuitbreaker-2.1.0.jar
...
```

2. **Check available Gradle tasks:**
```bash
./gradlew tasks --all | grep -i jarjar
```

3. **Install in Minecraft:**
   - Copy only `build/libs/steve-ai-mod-1.0.0.jar` to your `mods/` folder
   - Remove any old `-all.jar` files (from the Shadow build)
   - Start Minecraft/Forge
   - The Caffeine/dependency errors should be resolved

## Dependencies Bundled with jarJar

The following external dependencies are bundled using jarJar:

- **GraalVM Polyglot** (23.1.0)
  - `org.graalvm.polyglot:polyglot`
  - `org.graalvm.polyglot:js`

- **Resilience4j** (2.1.0)
  - `io.github.resilience4j:resilience4j-circuitbreaker`
  - `io.github.resilience4j:resilience4j-retry`
  - `io.github.resilience4j:resilience4j-ratelimiter`
  - `io.github.resilience4j:resilience4j-bulkhead`

- **Caffeine** (3.1.8)
  - `com.github.ben-manes.caffeine:caffeine`

- **Apache Commons Codec** (1.16.0)
  - `commons-codec:commons-codec`

## Reverting to ShadowJar (Not Recommended)

If you need to revert to ShadowJar for any reason:

1. Uncomment the `shadowJar { ... }` block in build.gradle
2. Replace all `jarJar '...'` declarations with `shadow '...'`
3. Run `./gradlew clean shadowJar`

However, this is **not recommended** for Forge 1.20.1 mods as it can cause runtime classloading issues.

## Troubleshooting

### "Could not find jarJar configuration"

Make sure you're using ForgeGradle 6.0+. The jarJar feature is built into modern ForgeGradle versions.

### Dependencies still missing at runtime

1. Clean your build: `./gradlew clean`
2. Rebuild: `./gradlew build`
3. Delete old JARs from your mods folder
4. Install only the new `steve-ai-mod-1.0.0.jar` (not the `-all.jar`)

### Build fails with plugin not found

Ensure your `settings.gradle` includes the MinecraftForge maven repository:

```gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
}
```

## References

- [ForgeGradle Documentation](https://docs.minecraftforge.net/en/latest/gettingstarted/)
- [Jar-in-Jar Dependencies](https://docs.minecraftforge.net/en/latest/gettingstarted/structuring/#jij-dependencies)
