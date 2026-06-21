package hifumi.kiyomizu

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DatabaseService {
    private const val DEFAULT_DB_FILE = "kiyomizu_companion.db"
    private const val APP_DIR_NAME = "Kiyomizu"

    data class ConfigPasswordRecord(
        val algorithm: String,
        val iterations: Int,
        val salt: String,
        val passwordHash: String
    )

    init {
        // Force loading SQLite driver
        Class.forName("org.sqlite.JDBC")
    }

    private fun getConnection(): Connection {
        return DriverManager.getConnection("jdbc:sqlite:${databaseFilePath()}")
    }

    fun databaseFilePath(): String {
        val explicitPath = System.getProperty("kiyomizu.db.file")?.takeIf { it.isNotBlank() }
            ?: System.getenv("KIYOMIZU_DB_FILE")?.takeIf { it.isNotBlank() }
        if (explicitPath != null) {
            return prepareDatabasePath(Paths.get(explicitPath), migrateLegacy = false).toString()
        }

        val managedPath = defaultManagedDatabasePath()
        return prepareDatabasePath(managedPath, migrateLegacy = true).toString()
    }

    private fun defaultManagedDatabasePath(): Path {
        val homeDir = System.getProperty("user.home") ?: "."
        val osName = (System.getProperty("os.name") ?: "").lowercase()
        return when {
            osName.contains("mac") -> Paths.get(homeDir, "Library", "Application Support", APP_DIR_NAME, DEFAULT_DB_FILE)
            osName.contains("win") -> {
                val appDataDir = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: homeDir
                Paths.get(appDataDir, APP_DIR_NAME, DEFAULT_DB_FILE)
            }
            else -> {
                val dataHomeDir = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: Paths.get(homeDir, ".local", "share").toString()
                Paths.get(dataHomeDir, APP_DIR_NAME, DEFAULT_DB_FILE)
            }
        }
    }

    private fun prepareDatabasePath(path: Path, migrateLegacy: Boolean): Path {
        val normalized = path.toAbsolutePath().normalize()
        normalized.parent?.let {
            Files.createDirectories(it)
            restrictDirectoryPermissions(it)
        }
        if (migrateLegacy) {
            migrateLegacyDatabaseIfNeeded(normalized)
        }
        return normalized
    }

    private fun migrateLegacyDatabaseIfNeeded(targetPath: Path) {
        val legacyPath = Paths.get(DEFAULT_DB_FILE).toAbsolutePath().normalize()
        if (legacyPath == targetPath || Files.exists(targetPath) || !Files.exists(legacyPath)) {
            return
        }

        try {
            moveIfExists(legacyPath, targetPath)
            moveIfExists(Paths.get("${legacyPath}-wal"), Paths.get("${targetPath}-wal"))
            moveIfExists(Paths.get("${legacyPath}-shm"), Paths.get("${targetPath}-shm"))
            println("Migrated legacy database from $legacyPath to $targetPath")
        } catch (e: Exception) {
            System.err.println("Failed to migrate legacy database from $legacyPath to $targetPath: ${e.message}")
        }
    }

    private fun moveIfExists(sourcePath: Path, targetPath: Path) {
        if (!Files.exists(sourcePath)) return
        targetPath.parent?.let { Files.createDirectories(it) }
        Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
    }

    fun initDatabase() {
        val dbPath = Paths.get(databaseFilePath())
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // WAL allows the async memory-extraction writer to coexist with the
                // per-request memory reader without lock contention.
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")

                // 1. relationship_state
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS relationship_state (
                        id INTEGER PRIMARY KEY,
                        intimacy REAL NOT NULL,
                        trust REAL NOT NULL,
                        mood TEXT NOT NULL,
                        last_interaction_at INTEGER NOT NULL,
                        last_decay_at INTEGER NOT NULL
                    )
                """.trimIndent())
                ensureRelationshipStateSchema(conn)

                // 2. memories
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        content TEXT NOT NULL,
                        vector BLOB NOT NULL,
                        type TEXT NOT NULL,
                        emotion_tag TEXT NOT NULL,
                        strength REAL NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_accessed_at INTEGER NOT NULL
                    )
                """.trimIndent())
                ensureMemoriesSchema(conn)

                // 3. reflections
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS reflections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        diary_entry TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())

                // 4. request_logs
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS request_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        at TEXT NOT NULL,
                        method TEXT NOT NULL,
                        pathname TEXT NOT NULL,
                        patched INTEGER NOT NULL,
                        removed_thinking_blocks INTEGER NOT NULL DEFAULT 0,
                        model TEXT,
                        message_count INTEGER,
                        explicit_cache_blocks INTEGER,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS app_config (
                        id INTEGER PRIMARY KEY,
                        config_json TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS config_auth (
                        id INTEGER PRIMARY KEY,
                        algorithm TEXT NOT NULL,
                        iterations INTEGER NOT NULL,
                        salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())

                // Insert default relationship state if empty
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM relationship_state")
                if (rs.next() && rs.getInt(1) == 0) {
                    val now = Instant.now().epochSecond
                    stmt.execute("""
                        INSERT INTO relationship_state (id, intimacy, trust, mood, last_interaction_at, last_decay_at)
                        VALUES (1, 10.0, 10.0, 'neutral', $now, $now)
                    """.trimIndent())
                }
            }
        }
        restrictFilePermissions(dbPath)
        restrictFilePermissions(Paths.get("${dbPath}-wal"))
        restrictFilePermissions(Paths.get("${dbPath}-shm"))
    }

    private fun restrictDirectoryPermissions(path: Path) {
        setPosixPermissionsIfSupported(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )
    }

    private fun restrictFilePermissions(path: Path) {
        if (!Files.exists(path)) return
        setPosixPermissionsIfSupported(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
        )
    }

    private fun setPosixPermissionsIfSupported(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Windows and some filesystems do not support POSIX permissions.
        } catch (_: Exception) {
            // Permission tightening is best-effort; failure must not prevent startup.
        }
    }

    fun loadConfig(): String? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT config_json FROM app_config WHERE id = 1").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    return rs.getString("config_json")
                }
            }
        }
        return null
    }

    fun saveConfig(configJson: String) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO app_config (id, config_json, updated_at)
                VALUES (1, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    config_json = excluded.config_json,
                    updated_at = excluded.updated_at
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, configJson)
                pstmt.setLong(2, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun loadConfigPassword(): ConfigPasswordRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT algorithm, iterations, salt, password_hash
                FROM config_auth
                WHERE id = 1
            """.trimIndent()).use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    return ConfigPasswordRecord(
                        algorithm = rs.getString("algorithm"),
                        iterations = rs.getInt("iterations"),
                        salt = rs.getString("salt"),
                        passwordHash = rs.getString("password_hash")
                    )
                }
            }
        }
        return null
    }

    fun saveConfigPassword(record: ConfigPasswordRecord) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO config_auth (id, algorithm, iterations, salt, password_hash, updated_at)
                VALUES (1, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    algorithm = excluded.algorithm,
                    iterations = excluded.iterations,
                    salt = excluded.salt,
                    password_hash = excluded.password_hash,
                    updated_at = excluded.updated_at
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, record.algorithm)
                pstmt.setInt(2, record.iterations)
                pstmt.setString(3, record.salt)
                pstmt.setString(4, record.passwordHash)
                pstmt.setLong(5, now)
                pstmt.executeUpdate()
            }
        }
    }

    private fun ensureRelationshipStateSchema(conn: Connection) {
        val columns = mutableSetOf<String>()
        conn.prepareStatement("PRAGMA table_info(relationship_state)").use { pstmt ->
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }

        if ("last_decay_at" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE relationship_state ADD COLUMN last_decay_at INTEGER NOT NULL DEFAULT 0")
            }
        }

        conn.prepareStatement("""
            UPDATE relationship_state
            SET last_decay_at = last_interaction_at
            WHERE last_decay_at = 0
        """.trimIndent()).use { pstmt ->
            pstmt.executeUpdate()
        }
    }

    /**
     * Incrementally evolve the memories table:
     *   - related_ids        : JSON array of associated memory ids (association graph edges)
     *   - access_count       : working-memory activation counter
     *   - emotion_valence    : continuous affect valence 0..1 (negative..positive)
     *   - emotion_arousal    : continuous affect arousal 0..1 (calm..intense)
     * Legacy discrete emotion_tag values are migrated once into valence/arousal.
     */
    private fun ensureMemoriesSchema(conn: Connection) {
        val columns = mutableSetOf<String>()
        conn.prepareStatement("PRAGMA table_info(memories)").use { pstmt ->
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }

        if ("related_ids" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE memories ADD COLUMN related_ids TEXT NOT NULL DEFAULT '[]'")
            }
        }
        if ("access_count" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE memories ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Track whether valence/arousal already exist before adding, so the one-shot
        // legacy emotion_tag -> valence/arousal migration only runs the first time.
        val hadValence = "emotion_valence" in columns
        if ("emotion_valence" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE memories ADD COLUMN emotion_valence REAL NOT NULL DEFAULT 0.5")
            }
        }
        if ("emotion_arousal" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE memories ADD COLUMN emotion_arousal REAL NOT NULL DEFAULT 0.3")
            }
        }

        if (!hadValence && "emotion_tag" in columns) {
            // One-shot migration of discrete emotion tags to the 2D affect space.
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    UPDATE memories SET emotion_valence = 0.85, emotion_arousal = 0.60 WHERE emotion_tag = 'joy' AND emotion_valence = 0.5
                """.trimIndent())
                stmt.execute("""
                    UPDATE memories SET emotion_valence = 0.20, emotion_arousal = 0.30 WHERE emotion_tag = 'sadness' AND emotion_valence = 0.5
                """.trimIndent())
                stmt.execute("""
                    UPDATE memories SET emotion_valence = 0.25, emotion_arousal = 0.85 WHERE emotion_tag = 'anxiety' AND emotion_valence = 0.5
                """.trimIndent())
                stmt.execute("""
                    UPDATE memories SET emotion_valence = 0.75, emotion_arousal = 0.35 WHERE emotion_tag = 'warmth' AND emotion_valence = 0.5
                """.trimIndent())
            }
        }
    }

    // Helper functions for Vector float array conversion
    fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }

    // --- Relationship State CRUD ---
    data class RelationshipState(
        val intimacy: Double,
        val trust: Double,
        val mood: String,
        val lastInteractionAt: Long,
        val lastDecayAt: Long
    )

    private fun readRelationshipState(conn: Connection): RelationshipState? {
        conn.prepareStatement("SELECT intimacy, trust, mood, last_interaction_at, last_decay_at FROM relationship_state WHERE id = 1").use { pstmt ->
            val rs = pstmt.executeQuery()
            if (rs.next()) {
                return RelationshipState(
                    intimacy = rs.getDouble("intimacy"),
                    trust = rs.getDouble("trust"),
                    mood = rs.getString("mood"),
                    lastInteractionAt = rs.getLong("last_interaction_at"),
                    lastDecayAt = rs.getLong("last_decay_at")
                )
            }
        }
        return null
    }

    fun getRelationshipState(): RelationshipState {
        getConnection().use { conn ->
            readRelationshipState(conn)?.let { return it }
        }
        // Fallback if somehow not found
        val now = Instant.now().epochSecond
        return RelationshipState(10.0, 10.0, "neutral", now, now)
    }

    fun updateRelationshipState(intimacy: Double, trust: Double, mood: String) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE relationship_state 
                SET intimacy = ?, trust = ?, mood = ?, last_interaction_at = ?, last_decay_at = ? 
                WHERE id = 1
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, intimacy.coerceIn(0.0, 100.0))
                pstmt.setDouble(2, trust.coerceIn(0.0, 100.0))
                pstmt.setString(3, mood)
                pstmt.setLong(4, now)
                pstmt.setLong(5, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun applyRelationshipDelta(intimacyDelta: Double, trustDelta: Double, mood: String) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE relationship_state
                SET intimacy = MIN(MAX(intimacy + ?, 0.0), 100.0),
                    trust = MIN(MAX(trust + ?, 0.0), 100.0),
                    mood = ?,
                    last_interaction_at = ?,
                    last_decay_at = ?
                WHERE id = 1
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, intimacyDelta)
                pstmt.setDouble(2, trustDelta)
                pstmt.setString(3, mood)
                pstmt.setLong(4, now)
                pstmt.setLong(5, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun decayIntimacy(decayRate: Double): Boolean {
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                // Grab the writer lock before reading so a concurrent relationship
                // update cannot slip between the read and the decay write-back.
                conn.prepareStatement("""
                    UPDATE relationship_state
                    SET last_decay_at = last_decay_at
                    WHERE id = 1
                """.trimIndent()).use { pstmt ->
                    pstmt.executeUpdate()
                }
                val state = readRelationshipState(conn) ?: run {
                    conn.commit()
                    return false
                }
                val now = Instant.now().epochSecond
                val baseline = maxOf(state.lastInteractionAt, state.lastDecayAt)
                val elapsedSeconds = now - baseline
                if (elapsedSeconds < 86400) {
                    conn.commit()
                    return false
                }

                val daysElapsed = elapsedSeconds.toDouble() / 86400.0
                val totalDecay = decayRate * daysElapsed
                val newIntimacy = (state.intimacy - totalDecay).coerceAtLeast(0.0)
                conn.prepareStatement("""
                    UPDATE relationship_state
                    SET intimacy = ?, last_decay_at = ?
                    WHERE id = 1
                """.trimIndent()).use { pstmt ->
                    pstmt.setDouble(1, newIntimacy)
                    pstmt.setLong(2, now)
                    pstmt.executeUpdate()
                }
                conn.commit()
                return newIntimacy != state.intimacy
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // --- Memories CRUD ---
    data class MemoryRecord(
        val id: Int,
        val content: String,
        val vector: FloatArray,
        val type: String,
        val emotionTag: String,
        val strength: Double,
        val createdAt: Long,
        val lastAccessedAt: Long,
        val relatedIds: List<Int> = emptyList(),
        val accessCount: Int = 0,
        val emotionValence: Double = 0.5,
        val emotionArousal: Double = 0.3
    )

    fun insertMemory(
        content: String,
        vector: FloatArray,
        type: String,
        emotionTag: String,
        initialStrength: Double,
        emotionValence: Double = 0.5,
        emotionArousal: Double = 0.3,
        relatedIds: List<Int> = emptyList()
    ): Int {
        val now = Instant.now().epochSecond
        val vectorBytes = floatArrayToBytes(vector)
        val relatedJson = relatedIds.joinToString(prefix = "[", postfix = "]")
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memories (content, vector, type, emotion_tag, strength, created_at, last_accessed_at, related_ids, access_count, emotion_valence, emotion_arousal)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, content)
                pstmt.setBytes(2, vectorBytes)
                pstmt.setString(3, type)
                pstmt.setString(4, emotionTag)
                pstmt.setDouble(5, initialStrength.coerceIn(0.0, 1.0))
                pstmt.setLong(6, now)
                pstmt.setLong(7, now)
                pstmt.setString(8, relatedJson)
                pstmt.setDouble(9, emotionValence.coerceIn(0.0, 1.0))
                pstmt.setDouble(10, emotionArousal.coerceIn(0.0, 1.0))
                pstmt.executeUpdate()
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return -1
    }

    fun updateMemoryAccessAndStrength(id: Int, strengthDelta: Double, maxStrength: Double) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memories
                SET strength = MIN(strength + ?, ?), last_accessed_at = ?, access_count = access_count + 1
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, strengthDelta)
                pstmt.setDouble(2, maxStrength)
                pstmt.setLong(3, now)
                pstmt.setInt(4, id)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Merge a list of associated memory ids into a memory's related_ids (deduped).
     */
    fun addRelatedIds(id: Int, additionalIds: List<Int>) {
        if (additionalIds.isEmpty()) return
        getConnection().use { conn ->
            conn.prepareStatement("SELECT related_ids FROM memories WHERE id = ?").use { pstmt ->
                pstmt.setInt(1, id)
                val rs = pstmt.executeQuery()
                if (!rs.next()) return
                val existing = parseRelatedIds(rs.getString("related_ids"))
                val merged = (existing + additionalIds).distinct().filter { it != id }
                conn.prepareStatement("UPDATE memories SET related_ids = ? WHERE id = ?").use { upd ->
                    upd.setString(1, merged.joinToString(prefix = "[", postfix = "]"))
                    upd.setInt(2, id)
                    upd.executeUpdate()
                }
            }
        }
    }

    private fun parseRelatedIds(json: String?): List<Int> {
        if (json.isNullOrBlank()) return emptyList()
        return json.trim().trim('[', ']').split(',')
            .mapNotNull { it.trim().toIntOrNull() }
    }

    fun decayAllMemories(decayRate: Double, threshold: Double) {
        // Decay is now lazy (Ebbinghaus exponential at read time). The periodic job
        // only garbage-collects memories whose lazy-decayed strength has fallen below
        // the threshold. decayRate is retained for API/compat but no longer subtracts.
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM memories WHERE strength < ?").use { pstmt ->
                pstmt.setDouble(1, threshold)
                pstmt.executeUpdate()
            }
        }
    }

    fun getAllMemoriesForSearch(): List<MemoryRecord> {
        val list = mutableListOf<MemoryRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT id, content, vector, type, emotion_tag, strength, created_at, last_accessed_at,
                       related_ids, access_count, emotion_valence, emotion_arousal
                FROM memories
            """.trimIndent()).use { pstmt ->
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    val vectorBytes = rs.getBytes("vector") ?: continue
                    val vector = bytesToFloatArray(vectorBytes)
                    list.add(
                        MemoryRecord(
                            id = rs.getInt("id"),
                            content = rs.getString("content"),
                            vector = vector,
                            type = rs.getString("type"),
                            emotionTag = rs.getString("emotion_tag"),
                            strength = rs.getDouble("strength"),
                            createdAt = rs.getLong("created_at"),
                            lastAccessedAt = rs.getLong("last_accessed_at"),
                            relatedIds = parseRelatedIds(rs.getString("related_ids")),
                            accessCount = rs.getInt("access_count"),
                            emotionValence = rs.getDouble("emotion_valence"),
                            emotionArousal = rs.getDouble("emotion_arousal")
                        )
                    )
                }
            }
        }
        return list
    }

    /**
     * Consolidation candidates: episodic memories reactivated (access_count > 0) within
     * [windowSeconds] whose affect is non-neutral (salience > 0), ordered by salience.
     */
    fun getConsolidationCandidates(windowSeconds: Long, limit: Int): List<MemoryRecord> {
        val cutoff = Instant.now().epochSecond - windowSeconds
        val list = mutableListOf<MemoryRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT id, content, vector, type, emotion_tag, strength, created_at, last_accessed_at,
                       related_ids, access_count, emotion_valence, emotion_arousal
                FROM memories
                WHERE type = 'episodic'
                  AND last_accessed_at >= ?
                  AND access_count > 0
                  AND (emotion_arousal > 0.3 OR emotion_valence < 0.45 OR emotion_valence > 0.55)
                ORDER BY emotion_arousal DESC, access_count DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, cutoff)
                pstmt.setInt(2, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    val vectorBytes = rs.getBytes("vector") ?: continue
                    list.add(
                        MemoryRecord(
                            id = rs.getInt("id"),
                            content = rs.getString("content"),
                            vector = bytesToFloatArray(vectorBytes),
                            type = rs.getString("type"),
                            emotionTag = rs.getString("emotion_tag"),
                            strength = rs.getDouble("strength"),
                            createdAt = rs.getLong("created_at"),
                            lastAccessedAt = rs.getLong("last_accessed_at"),
                            relatedIds = parseRelatedIds(rs.getString("related_ids")),
                            accessCount = rs.getInt("access_count"),
                            emotionValence = rs.getDouble("emotion_valence"),
                            emotionArousal = rs.getDouble("emotion_arousal")
                        )
                    )
                }
            }
        }
        return list
    }

    fun getRelatedEdgesTotal(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT related_ids FROM memories").use { pstmt ->
                val rs = pstmt.executeQuery()
                var total = 0
                while (rs.next()) {
                    total += parseRelatedIds(rs.getString("related_ids")).size
                }
                return total
            }
        }
    }

    fun getAffectDistribution(): JsonObject {
        // Coarse buckets over all memories.
        val buckets = mutableMapOf(
            "positive_calm" to 0, "positive_intense" to 0,
            "negative_calm" to 0, "negative_intense" to 0,
            "neutral" to 0
        )
        getConnection().use { conn ->
            conn.prepareStatement("SELECT emotion_valence, emotion_arousal FROM memories").use { pstmt ->
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    val v = rs.getDouble("emotion_valence")
                    val a = rs.getDouble("emotion_arousal")
                    val key = when {
                        v >= 0.55 && a >= 0.5 -> "positive_intense"
                        v >= 0.55 -> "positive_calm"
                        v <= 0.45 && a >= 0.5 -> "negative_intense"
                        v <= 0.45 -> "negative_calm"
                        else -> "neutral"
                    }
                    buckets[key] = (buckets[key] ?: 0) + 1
                }
            }
        }
        return buildJsonObject {
            buckets.forEach { (k, c) -> put(k, c) }
        }
    }

    // --- Reflections CRUD ---
    fun insertReflection(diaryEntry: String) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("INSERT INTO reflections (diary_entry, created_at) VALUES (?, ?)")
                .use { pstmt ->
                    pstmt.setString(1, diaryEntry)
                    pstmt.setLong(2, now)
                    pstmt.executeUpdate()
                }
        }
    }

    fun getRecentReflections(limit: Int): List<String> {
        val list = mutableListOf<String>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT diary_entry FROM reflections ORDER BY created_at DESC LIMIT ?")
                .use { pstmt ->
                    pstmt.setInt(1, limit)
                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        list.add(rs.getString("diary_entry"))
                    }
                }
        }
        return list
    }

    // --- Reflection detail CRUD ---
    data class ReflectionRecord(
        val id: Int,
        val diaryEntry: String,
        val createdAt: Long
    )

    fun getRecentReflectionsDetailed(limit: Int): List<ReflectionRecord> {
        val list = mutableListOf<ReflectionRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT id, diary_entry, created_at FROM reflections ORDER BY created_at DESC LIMIT ?")
                .use { pstmt ->
                    pstmt.setInt(1, limit)
                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        list.add(ReflectionRecord(
                            id = rs.getInt("id"),
                            diaryEntry = rs.getString("diary_entry"),
                            createdAt = rs.getLong("created_at")
                        ))
                    }
                }
        }
        return list
    }

    fun getMemoryCount(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM memories").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    // --- Request logs CRUD ---
    data class RequestLogRecord(
        val id: Int,
        val at: String,
        val method: String,
        val pathname: String,
        val patched: Boolean,
        val removedThinkingBlocks: Int,
        val model: String?,
        val messageCount: Int?,
        val explicitCacheBlocks: Int?
    )

    fun insertRequestLog(
        at: String,
        method: String,
        pathname: String,
        patched: Boolean,
        removedThinkingBlocks: Int,
        model: String?,
        messageCount: Int?,
        explicitCacheBlocks: Int?
    ) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO request_logs (at, method, pathname, patched, removed_thinking_blocks, model, message_count, explicit_cache_blocks, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, at)
                pstmt.setString(2, method)
                pstmt.setString(3, pathname)
                pstmt.setInt(4, if (patched) 1 else 0)
                pstmt.setInt(5, removedThinkingBlocks)
                if (model != null) pstmt.setString(6, model) else pstmt.setNull(6, java.sql.Types.VARCHAR)
                if (messageCount != null) pstmt.setInt(7, messageCount) else pstmt.setNull(7, java.sql.Types.INTEGER)
                if (explicitCacheBlocks != null) pstmt.setInt(8, explicitCacheBlocks) else pstmt.setNull(8, java.sql.Types.INTEGER)
                pstmt.setLong(9, now)
                pstmt.executeUpdate()
            }
            // Keep only the newest 1000 entries
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM request_logs WHERE id NOT IN (SELECT id FROM request_logs ORDER BY id DESC LIMIT 1000)")
            }
        }
    }

    fun getRecentRequestLogs(limit: Int): List<RequestLogRecord> {
        val list = mutableListOf<RequestLogRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT id, at, method, pathname, patched, removed_thinking_blocks, model, message_count, explicit_cache_blocks
                FROM request_logs ORDER BY id DESC LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(RequestLogRecord(
                        id = rs.getInt("id"),
                        at = rs.getString("at"),
                        method = rs.getString("method"),
                        pathname = rs.getString("pathname"),
                        patched = rs.getInt("patched") != 0,
                        removedThinkingBlocks = rs.getInt("removed_thinking_blocks"),
                        model = rs.getString("model"),
                        messageCount = rs.getObject("message_count") as? Int,
                        explicitCacheBlocks = rs.getObject("explicit_cache_blocks") as? Int
                    ))
                }
            }
        }
        return list
    }
}
