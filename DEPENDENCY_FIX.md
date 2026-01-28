# Dependency Bundling Fix for NoClassDefFoundError

## Problem
The mod was failing at runtime with:
```
java.lang.NoClassDefFoundError: com/github/benmanes/caffeine/cache/Caffeine
```

This occurred because external dependencies (Caffeine, Apache Commons Codec, Resilience4j, GraalVM) were declared but not bundled into the mod JAR file.

## Solution Implemented
Added the Shadow Gradle plugin to bundle dependencies into the mod JAR:

1. **Added Shadow Plugin** (line 6 in build.gradle):
   ```gradle
   id 'com.github.johnrengelman.shadow' version '8.1.1'
   ```

2. **Declared Shadow Dependencies** (lines 63-82 in build.gradle):
   - Caffeine 3.1.8
   - Apache Commons Codec 1.16.0
   - Resilience4j libraries (circuitbreaker, retry, ratelimiter, bulkhead)
   - GraalVM Polyglot libraries

3. **Configured Shadow JAR Task** (lines 113-127 in build.gradle):
   - Package relocation to avoid conflicts with other mods
   - No minimization (would break reflection-based libraries)
   - Integration with reobfJar for Forge compatibility
   - Classifier 'all' for output JAR naming

## Package Relocations
To prevent conflicts with other mods, packages are relocated:
- `com.github.benmanes.caffeine` → `com.steve.ai.shaded.caffeine`
- `org.apache.commons.codec` → `com.steve.ai.shaded.codec`
- `io.github.resilience4j` → `com.steve.ai.shaded.resilience4j`
- `org.graalvm.polyglot` → `com.steve.ai.shaded.graalvm`

Note: Minimization is NOT used as these libraries rely on reflection and dynamic class loading.

## Build Instructions
Once the ForgeGradle version issue is resolved:
```bash
./gradlew clean shadowJar
# Output will be in build/libs/steve-ai-mod-1.0.0-all.jar
# The '-all' suffix indicates this is the shadowed JAR with bundled dependencies
```

## Alternative Quick Fix (Workaround)
If you cannot rebuild the mod, you can manually add the dependencies to your mods folder:

1. Download these JAR files:
   - [Caffeine 3.1.8](https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8.jar)
   - [Commons Codec 1.16.0](https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar)
   - Resilience4j JARs (if needed for other features)

2. Place them in your Minecraft `mods` folder alongside steve-ai-mod.jar

3. Minecraft Forge will load them as library mods

Note: This workaround bypasses package relocation, which could cause conflicts with other mods using the same libraries.

## Outstanding Issue
The build.gradle has a pre-existing issue with ForgeGradle version `[6.0,6.2)` which doesn't exist in the Maven repository.
For Minecraft Forge 1.20.1, this should likely be changed to a specific version like `5.1.53` or use the newer NeoGradle system.

