# Dependency Bundling Fix for NoClassDefFoundError

## Problem
The mod was failing at runtime with:
```
java.lang.NoClassDefFoundError: com/github/benmanes/caffeine/cache/Caffeine
```

This occurred because external dependencies (Caffeine, Apache Commons Codec, Resilience4j, GraalVM) were declared but not bundled into the mod JAR file.

## Solution Implemented ✅

**Updated Solution (Current):** Using **jarJar** for Forge-native dependency bundling.

See [JARJAR_MIGRATION.md](JARJAR_MIGRATION.md) for complete details.

### Why jarJar Instead of ShadowJar?

1. **Forge Integration**: jarJar is built into ForgeGradle and integrates seamlessly with the reobfJar process
2. **Proper Format**: Dependencies are embedded as jar-in-jar (META-INF/jarjar/) instead of a fat JAR
3. **No Relocation Needed**: Dependencies are isolated, preventing conflicts without package relocation
4. **Reliable**: Resolves classloading and mapping issues with Forge 1.20.1

### Changes Made

1. **Declared jarJar Dependencies** (build.gradle):
   - Caffeine 3.1.8
   - Apache Commons Codec 1.16.0
   - Resilience4j libraries (circuitbreaker, retry, ratelimiter, bulkhead)
   - GraalVM Polyglot libraries

2. **Disabled ShadowJar**: The shadowJar configuration is now commented out (kept for reference)

## Build Instructions
```bash
./gradlew clean build
# Output will be in build/libs/steve-ai-mod-1.0.0.jar
```

The built JAR will contain all dependencies embedded as jar-in-jar in META-INF/jarjar/.

## Verification
```bash
jar tf build/libs/steve-ai-mod-1.0.0.jar | grep jarjar
```

You should see entries like:
- META-INF/jarjar/caffeine-3.1.8.jar
- META-INF/jarjar/resilience4j-circuitbreaker-2.1.0.jar
- etc.

## Previous Solution (Deprecated)

~~Previously used Shadow Gradle plugin with package relocation. This has been replaced with jarJar for better Forge compatibility.~~

For historical reference, the Shadow approach:
- Used package relocation (e.g., `com.github.benmanes.caffeine` → `com.steve.ai.shaded.caffeine`)
- Created a fat JAR with `-all` classifier
- Required manual reobfJar integration

## Alternative Quick Fix (Workaround)
If you cannot rebuild the mod, you can manually add the dependencies to your mods folder:

1. Download these JAR files:
   - [Caffeine 3.1.8](https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar)
   - [Commons Codec 1.16.0](https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar)
   - Resilience4j JARs (if needed for other features)

2. Place them in your Minecraft `mods` folder alongside steve-ai-mod.jar

3. Minecraft Forge will load them as library mods

Note: This workaround bypasses proper bundling and could cause conflicts with other mods using the same libraries.


