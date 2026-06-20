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

                // 3. reflections
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS reflections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        diary_entry TEXT NOT NULL,
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
        val lastAccessedAt: Long
    )

    fun insertMemory(content: String, vector: FloatArray, type: String, emotionTag: String, initialStrength: Double) {
        val now = Instant.now().epochSecond
        val vectorBytes = floatArrayToBytes(vector)
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memories (content, vector, type, emotion_tag, strength, created_at, last_accessed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, content)
                pstmt.setBytes(2, vectorBytes)
                pstmt.setString(3, type)
                pstmt.setString(4, emotionTag)
                pstmt.setDouble(5, initialStrength.coerceIn(0.0, 1.0))
                pstmt.setLong(6, now)
                pstmt.setLong(7, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun updateMemoryAccessAndStrength(id: Int, strengthDelta: Double, maxStrength: Double) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memories 
                SET strength = MIN(strength + ?, ?), last_accessed_at = ? 
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

    fun decayAllMemories(decayRate: Double, threshold: Double) {
        getConnection().use { conn ->
            // 1. Decay memory strengths
            conn.prepareStatement("UPDATE memories SET strength = strength - ?").use { pstmt ->
                pstmt.setDouble(1, decayRate)
                pstmt.executeUpdate()
            }
            // 2. Delete memories below threshold
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
                SELECT id, content, vector, type, emotion_tag, strength, created_at, last_accessed_at 
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
                            lastAccessedAt = rs.getLong("last_accessed_at")
                        )
                    )
                }
            }
        }
        return list
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
}
