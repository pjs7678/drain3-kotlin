# Drain3 Kotlin

Kotlin/JVM port of [Drain3](https://github.com/IBM/Drain3) — an online log template miner that extracts templates (clusters) from a stream of log messages in real-time.

Based on the Drain algorithm described in:
> Pinjia He, Jieming Zhu, Zibin Zheng, and Michael R. Lyu. [Drain: An Online Log Parsing Approach with Fixed Depth Tree](http://jiemingzhu.github.io/pub/pjhe_icws2017.pdf), Proceedings of the 24th International Conference on Web Services (ICWS), 2017.

## How It Works

Drain uses a fixed-depth prefix tree to cluster log messages by structure. Variable parts (usernames, IPs, numbers) become wildcards, leaving you with concise templates.

**Input:**
```
User john logged in from 192.168.1.1
User jane logged in from 10.0.0.2
Connection established to host server1 port 8080
Connection established to host server2 port 9090
Error: file not found at path /tmp/data.txt
Error: file not found at path /var/log/app.log
```

**Output:**
```
ID=1  : size=2  : User <*> logged in from <IP>
ID=2  : size=2  : Connection established to host <*> port <NUM>
ID=3  : size=2  : Error: file not found at path <*>
```

## Quick Start

```kotlin
val config = TemplateMinerConfig().apply {
    maskingInstructions = listOf(
        MaskingInstruction("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""", "IP"),
        MaskingInstruction("""((?<=[^A-Za-z0-9])|^)([\-+]?\d+)((?=[^A-Za-z0-9])|$)""", "NUM")
    )
}
val miner = TemplateMiner(config = config)

val result = miner.addLogMessage("User john logged in from 192.168.1.1")
println(result["template_mined"]) // "User john logged in from <IP>"

miner.addLogMessage("User jane logged in from 10.0.0.2")
println(miner.drain.clusters.first().getTemplate()) // "User <*> logged in from <IP>"
```

### Training vs Inference

```kotlin
// Training: clusters are created and updated
val result = miner.addLogMessage(logLine)

// Inference: match against existing clusters only (no mutation)
val cluster = miner.match(logLine)
```

### Parameter Extraction

```kotlin
val result = miner.addLogMessage("Request completed in 150 ms with status 200")
miner.addLogMessage("Request completed in 300 ms with status 404")

val template = result["template_mined"] as String
// "Request completed in <NUM> ms with status <NUM>"

val params = miner.extractParameters(template, "Request completed in 250 ms with status 500")
// [ExtractedParameter(value="250", maskName="NUM"), ExtractedParameter(value="500", maskName="NUM")]
```

## Configuration

### Programmatic

```kotlin
val config = TemplateMinerConfig().apply {
    drainSimTh = 0.4                // similarity threshold (default: 0.4)
    drainDepth = 4                  // prefix tree depth (default: 4, minimum: 3)
    drainMaxChildren = 100          // max children per node (default: 100)
    drainMaxClusters = null         // max clusters, null = unlimited (LRU eviction when set)
    drainExtraDelimiters = listOf("_", "=")
    parametrizeNumericTokens = true  // treat numeric tokens as wildcards
    snapshotIntervalMinutes = 5     // periodic save interval
    snapshotCompressState = true    // zlib compress snapshots
    maskPrefix = "<"
    maskSuffix = ">"
    maskingInstructions = listOf(
        MaskingInstruction(pattern, maskName),
        // ...
    )
}
```

### INI File

```kotlin
val config = TemplateMinerConfig().apply { load("drain3.ini") }
```

```ini
[DRAIN]
sim_th = 0.4
depth = 4
max_children = 100
max_clusters = 1000
extra_delimiters = ["_", ":"]
parametrize_numeric_tokens = true

[MASKING]
masking = [
    {"regex_pattern": "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "mask_with": "IP"},
    {"regex_pattern": "((?<=[^A-Za-z0-9])|^)([\\-\\+]?\\d+)((?=[^A-Za-z0-9])|$)", "mask_with": "NUM"}
]
mask_prefix = <
mask_suffix = >

[SNAPSHOT]
snapshot_interval_minutes = 5
compress_state = true

[PROFILING]
enabled = false
report_sec = 60
```

## Persistence

State is saved automatically on cluster creation/change and periodically. On restart, the full model (prefix tree + all clusters) is restored.

### Redis (via Lettuce)

```kotlin
val persistence = RedisPersistence(
    redisHost = "localhost",
    redisPort = 6379,
    redisDb = 0,
    redisPass = null,
    isSsl = false,
    redisKey = "drain3:state"
)
val miner = TemplateMiner(persistenceHandler = persistence, config = config)
```

Requires `io.lettuce:lettuce-core` on the classpath.

### File

```kotlin
val persistence = FilePersistence("/var/lib/drain3/state.bin")
val miner = TemplateMiner(persistenceHandler = persistence, config = config)
```

### Memory (for testing)

```kotlin
val persistence = MemoryBufferPersistence()
val miner = TemplateMiner(persistenceHandler = persistence, config = config)
```

### Custom

Implement the `PersistenceHandler` interface:

```kotlin
interface PersistenceHandler {
    fun saveState(state: ByteArray)
    fun loadState(): ByteArray?
}
```

## Masking

Masking replaces variable parts of log messages with named wildcards **before** Drain processes them. This improves clustering accuracy.

```kotlin
val instructions = listOf(
    MaskingInstruction("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""", "IP"),
    MaskingInstruction("""((?<=[^A-Za-z0-9])|^)(0x[a-fA-F0-9]+)((?=[^A-Za-z0-9])|$)""", "HEX"),
    MaskingInstruction("""((?<=[^A-Za-z0-9])|^)([\-+]?\d+)((?=[^A-Za-z0-9])|$)""", "NUM"),
)
```

Parameters not matching any mask are replaced with `<*>` by Drain core.

## Build

```bash
# Requires Java 21
./gradlew build

# Run tests
./gradlew test
```

## Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `jackson-databind` + `jackson-module-kotlin` | Required | State serialization |
| `io.lettuce:lettuce-core` | Optional | Redis persistence |

## Origin

1:1 port of [IBM/Drain3](https://github.com/IBM/Drain3) v0.9.11 (Python) to Kotlin/JVM.

| Python | Kotlin |
|--------|--------|
| `jsonpickle` | Jackson |
| `cachetools.LRUCache` | `LinkedHashMap(accessOrder=true)` |
| `configparser` (INI) | Custom INI parser |
| `redis` | Lettuce |
| `zlib + base64` | `Deflater/Inflater + Base64` |

## License

MIT
