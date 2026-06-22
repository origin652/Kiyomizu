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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
                stmt.execute("PRAGMA foreign_keys=ON")

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

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_nodes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uri TEXT NOT NULL UNIQUE,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        normalized_text TEXT NOT NULL,
                        searchable_text TEXT NOT NULL,
                        keywords TEXT NOT NULL DEFAULT '[]',
                        aliases TEXT NOT NULL DEFAULT '[]',
                        entities TEXT NOT NULL DEFAULT '[]',
                        topics TEXT NOT NULL DEFAULT '[]',
                        trigger_phrases TEXT NOT NULL DEFAULT '[]',
                        disclosure TEXT NOT NULL DEFAULT 'private',
                        priority REAL NOT NULL DEFAULT 0.5,
                        confidence REAL NOT NULL DEFAULT 0.5,
                        strength REAL NOT NULL DEFAULT 1.0,
                        emotion_valence REAL NOT NULL DEFAULT 0.5,
                        emotion_arousal REAL NOT NULL DEFAULT 0.3,
                        scope_hint TEXT,
                        person_uri TEXT,
                        project_uri TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        last_accessed_at INTEGER NOT NULL,
                        access_count INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'active',
                        source TEXT NOT NULL DEFAULT 'conversation',
                        raw_evidence TEXT
                    )
                """.trimIndent())
                ensureMemoryNodesSchema(conn)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        from_node_id INTEGER NOT NULL,
                        to_node_id INTEGER NOT NULL,
                        relation TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(from_node_id, to_node_id, relation),
                        FOREIGN KEY(from_node_id) REFERENCES memory_nodes(id) ON DELETE CASCADE,
                        FOREIGN KEY(to_node_id) REFERENCES memory_nodes(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_search_terms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        node_id INTEGER NOT NULL,
                        term TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        UNIQUE(node_id, term, kind),
                        FOREIGN KEY(node_id) REFERENCES memory_nodes(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_observations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        candidate_uri TEXT,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        normalized_text TEXT NOT NULL,
                        searchable_text TEXT NOT NULL,
                        keywords TEXT NOT NULL DEFAULT '[]',
                        aliases TEXT NOT NULL DEFAULT '[]',
                        entities TEXT NOT NULL DEFAULT '[]',
                        topics TEXT NOT NULL DEFAULT '[]',
                        trigger_phrases TEXT NOT NULL DEFAULT '[]',
                        person_uri TEXT,
                        project_uri TEXT,
                        scope_hint TEXT,
                        priority REAL NOT NULL DEFAULT 0.5,
                        confidence REAL NOT NULL DEFAULT 0.5,
                        emotion_valence REAL NOT NULL DEFAULT 0.5,
                        emotion_arousal REAL NOT NULL DEFAULT 0.3,
                        novelty REAL NOT NULL DEFAULT 0.5,
                        source TEXT NOT NULL DEFAULT 'conversation',
                        raw_evidence TEXT,
                        status TEXT NOT NULL DEFAULT 'buffered',
                        matched_node_id INTEGER,
                        seen_count INTEGER NOT NULL DEFAULT 1,
                        first_seen_at INTEGER NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_observation_terms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        observation_id INTEGER NOT NULL,
                        term TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        weight REAL NOT NULL DEFAULT 1.0,
                        UNIQUE(observation_id, term, kind),
                        FOREIGN KEY(observation_id) REFERENCES memory_observations(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dream_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        started_at INTEGER NOT NULL,
                        finished_at INTEGER,
                        mode TEXT NOT NULL,
                        status TEXT NOT NULL,
                        input_node_count INTEGER NOT NULL DEFAULT 0,
                        merged_count INTEGER NOT NULL DEFAULT 0,
                        archived_count INTEGER NOT NULL DEFAULT 0,
                        tombstoned_count INTEGER NOT NULL DEFAULT 0,
                        created_dream_count INTEGER NOT NULL DEFAULT 0,
                        created_consolidated_count INTEGER NOT NULL DEFAULT 0,
                        skipped_count INTEGER NOT NULL DEFAULT 0,
                        error TEXT,
                        dream_summary TEXT,
                        dream_journal TEXT,
                        dream_symbols TEXT NOT NULL DEFAULT '[]',
                        dream_emotions TEXT NOT NULL DEFAULT '[]',
                        next_allowed_at INTEGER
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dream_run_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        dream_run_id INTEGER NOT NULL,
                        node_id INTEGER,
                        node_uri TEXT,
                        operation TEXT NOT NULL,
                        reason TEXT,
                        result TEXT NOT NULL,
                        target_node_id INTEGER,
                        target_uri TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(dream_run_id) REFERENCES dream_runs(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_recycle_bin (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        node_id INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        content TEXT,
                        raw_evidence TEXT,
                        keywords TEXT NOT NULL DEFAULT '[]',
                        topics TEXT NOT NULL DEFAULT '[]',
                        reason TEXT,
                        created_at INTEGER NOT NULL,
                        purge_after INTEGER NOT NULL
                    )
                """.trimIndent())

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_nodes_kind ON memory_nodes(kind)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_nodes_person_uri ON memory_nodes(person_uri)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_nodes_project_uri ON memory_nodes(project_uri)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_nodes_scope_hint ON memory_nodes(scope_hint)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_nodes_strength ON memory_nodes(strength DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_nodes_status ON memory_nodes(status)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_edges_from_relation ON memory_edges(from_node_id, relation)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_edges_to_relation ON memory_edges(to_node_id, relation)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_search_terms_term ON memory_search_terms(term)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_search_terms_kind ON memory_search_terms(kind)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observations_status_expires ON memory_observations(status, expires_at)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observations_kind ON memory_observations(kind)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observations_person_uri ON memory_observations(person_uri)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observations_project_uri ON memory_observations(project_uri)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observations_candidate_uri ON memory_observations(candidate_uri)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observations_last_seen ON memory_observations(last_seen_at DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_observation_terms_term ON memory_observation_terms(term)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_dream_runs_started_at ON dream_runs(started_at DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_recycle_bin_purge_after ON memory_recycle_bin(purge_after)")

                try {
                    stmt.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS memory_nodes_fts USING fts5(
                            node_id UNINDEXED,
                            content,
                            searchable_text,
                            keywords,
                            aliases,
                            entities,
                            topics,
                            trigger_phrases
                        )
                    """.trimIndent())
                } catch (_: Exception) {
                    // FTS5 is optional. Search falls back to LIKE and search_terms matching.
                }

                try {
                    stmt.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS memory_observations_fts USING fts5(
                            observation_id UNINDEXED,
                            content,
                            searchable_text,
                            keywords,
                            aliases,
                            entities,
                            topics,
                            trigger_phrases
                        )
                    """.trimIndent())
                } catch (_: Exception) {
                    // FTS5 is optional. Search falls back to LIKE and observation_terms matching.
                }

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

    private fun ensureMemoryNodesSchema(conn: Connection) {
        val columns = mutableSetOf<String>()
        conn.prepareStatement("PRAGMA table_info(memory_nodes)").use { pstmt ->
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }

        if ("status" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE memory_nodes ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
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

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) {
            setNull(index, java.sql.Types.VARCHAR)
        } else {
            setString(index, value)
        }
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

    // --- Graph memory CRUD ---
    data class MemoryNodeRecord(
        val id: Int,
        val uri: String,
        val kind: String,
        val content: String,
        val normalizedText: String,
        val searchableText: String,
        val keywords: List<String>,
        val aliases: List<String>,
        val entities: List<String>,
        val topics: List<String>,
        val triggerPhrases: List<String>,
        val disclosure: String,
        val priority: Double,
        val confidence: Double,
        val strength: Double,
        val emotionValence: Double,
        val emotionArousal: Double,
        val scopeHint: String?,
        val personUri: String?,
        val projectUri: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val lastAccessedAt: Long,
        val accessCount: Int,
        val status: String,
        val source: String,
        val rawEvidence: String?
    )

    data class MemoryNodeDraft(
        val uri: String,
        val kind: String,
        val content: String,
        val normalizedText: String,
        val searchableText: String,
        val keywords: List<String> = emptyList(),
        val aliases: List<String> = emptyList(),
        val entities: List<String> = emptyList(),
        val topics: List<String> = emptyList(),
        val triggerPhrases: List<String> = emptyList(),
        val disclosure: String = "private",
        val priority: Double = 0.5,
        val confidence: Double = 0.5,
        val strength: Double = 1.0,
        val emotionValence: Double = 0.5,
        val emotionArousal: Double = 0.3,
        val scopeHint: String? = null,
        val personUri: String? = null,
        val projectUri: String? = null,
        val status: String = "active",
        val source: String = "conversation",
        val rawEvidence: String? = null
    )

    data class MemoryEdgeRecord(
        val id: Int,
        val fromNodeId: Int,
        val toNodeId: Int,
        val relation: String,
        val weight: Double,
        val createdAt: Long,
        val updatedAt: Long
    )

    data class MemoryEdgeDraft(
        val fromNodeId: Int,
        val toNodeId: Int,
        val relation: String,
        val weight: Double = 1.0
    )

    data class MemorySearchTermDraft(
        val term: String,
        val kind: String,
        val weight: Double = 1.0
    )

    data class MemoryObservationRecord(
        val id: Int,
        val candidateUri: String?,
        val kind: String,
        val content: String,
        val normalizedText: String,
        val searchableText: String,
        val keywords: List<String>,
        val aliases: List<String>,
        val entities: List<String>,
        val topics: List<String>,
        val triggerPhrases: List<String>,
        val personUri: String?,
        val projectUri: String?,
        val scopeHint: String?,
        val priority: Double,
        val confidence: Double,
        val emotionValence: Double,
        val emotionArousal: Double,
        val novelty: Double,
        val source: String,
        val rawEvidence: String?,
        val status: String,
        val matchedNodeId: Int?,
        val seenCount: Int,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val expiresAt: Long
    )

    data class MemoryObservationDraft(
        val candidateUri: String?,
        val kind: String,
        val content: String,
        val normalizedText: String,
        val searchableText: String,
        val keywords: List<String> = emptyList(),
        val aliases: List<String> = emptyList(),
        val entities: List<String> = emptyList(),
        val topics: List<String> = emptyList(),
        val triggerPhrases: List<String> = emptyList(),
        val personUri: String? = null,
        val projectUri: String? = null,
        val scopeHint: String? = null,
        val priority: Double = 0.5,
        val confidence: Double = 0.5,
        val emotionValence: Double = 0.5,
        val emotionArousal: Double = 0.3,
        val novelty: Double = 0.5,
        val source: String = "conversation",
        val rawEvidence: String? = null,
        val expiresAt: Long
    )

    data class DreamRunRecord(
        val id: Int,
        val startedAt: Long,
        val finishedAt: Long?,
        val mode: String,
        val status: String,
        val inputNodeCount: Int,
        val mergedCount: Int,
        val archivedCount: Int,
        val tombstonedCount: Int,
        val createdDreamCount: Int,
        val createdConsolidatedCount: Int,
        val skippedCount: Int,
        val error: String?,
        val dreamSummary: String?,
        val dreamJournal: String?,
        val dreamSymbols: List<String>,
        val dreamEmotions: List<String>,
        val nextAllowedAt: Long?
    )

    data class DreamRunDraft(
        val mode: String,
        val status: String,
        val inputNodeCount: Int = 0,
        val mergedCount: Int = 0,
        val archivedCount: Int = 0,
        val tombstonedCount: Int = 0,
        val createdDreamCount: Int = 0,
        val createdConsolidatedCount: Int = 0,
        val skippedCount: Int = 0,
        val error: String? = null,
        val dreamSummary: String? = null,
        val dreamJournal: String? = null,
        val dreamSymbols: List<String> = emptyList(),
        val dreamEmotions: List<String> = emptyList(),
        val nextAllowedAt: Long? = null
    )

    private fun encodeStringList(values: List<String>): String {
        return buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        }.toString()
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
            array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readMemoryNode(rs: java.sql.ResultSet): MemoryNodeRecord {
        return MemoryNodeRecord(
            id = rs.getInt("id"),
            uri = rs.getString("uri"),
            kind = rs.getString("kind"),
            content = rs.getString("content"),
            normalizedText = rs.getString("normalized_text"),
            searchableText = rs.getString("searchable_text"),
            keywords = parseStringList(rs.getString("keywords")),
            aliases = parseStringList(rs.getString("aliases")),
            entities = parseStringList(rs.getString("entities")),
            topics = parseStringList(rs.getString("topics")),
            triggerPhrases = parseStringList(rs.getString("trigger_phrases")),
            disclosure = rs.getString("disclosure"),
            priority = rs.getDouble("priority"),
            confidence = rs.getDouble("confidence"),
            strength = rs.getDouble("strength"),
            emotionValence = rs.getDouble("emotion_valence"),
            emotionArousal = rs.getDouble("emotion_arousal"),
            scopeHint = rs.getString("scope_hint"),
            personUri = rs.getString("person_uri"),
            projectUri = rs.getString("project_uri"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            lastAccessedAt = rs.getLong("last_accessed_at"),
            accessCount = rs.getInt("access_count"),
            status = rs.getString("status") ?: "active",
            source = rs.getString("source"),
            rawEvidence = rs.getString("raw_evidence")
        )
    }

    private fun readMemoryEdge(rs: java.sql.ResultSet): MemoryEdgeRecord {
        return MemoryEdgeRecord(
            id = rs.getInt("id"),
            fromNodeId = rs.getInt("from_node_id"),
            toNodeId = rs.getInt("to_node_id"),
            relation = rs.getString("relation"),
            weight = rs.getDouble("weight"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at")
        )
    }

    private fun readMemoryObservation(rs: java.sql.ResultSet): MemoryObservationRecord {
        val matched = rs.getInt("matched_node_id")
        val matchedNull = rs.wasNull()
        return MemoryObservationRecord(
            id = rs.getInt("id"),
            candidateUri = rs.getString("candidate_uri"),
            kind = rs.getString("kind"),
            content = rs.getString("content"),
            normalizedText = rs.getString("normalized_text"),
            searchableText = rs.getString("searchable_text"),
            keywords = parseStringList(rs.getString("keywords")),
            aliases = parseStringList(rs.getString("aliases")),
            entities = parseStringList(rs.getString("entities")),
            topics = parseStringList(rs.getString("topics")),
            triggerPhrases = parseStringList(rs.getString("trigger_phrases")),
            personUri = rs.getString("person_uri"),
            projectUri = rs.getString("project_uri"),
            scopeHint = rs.getString("scope_hint"),
            priority = rs.getDouble("priority"),
            confidence = rs.getDouble("confidence"),
            emotionValence = rs.getDouble("emotion_valence"),
            emotionArousal = rs.getDouble("emotion_arousal"),
            novelty = rs.getDouble("novelty"),
            source = rs.getString("source"),
            rawEvidence = rs.getString("raw_evidence"),
            status = rs.getString("status"),
            matchedNodeId = if (matchedNull) null else matched,
            seenCount = rs.getInt("seen_count"),
            firstSeenAt = rs.getLong("first_seen_at"),
            lastSeenAt = rs.getLong("last_seen_at"),
            expiresAt = rs.getLong("expires_at")
        )
    }

    private fun readDreamRun(rs: java.sql.ResultSet): DreamRunRecord {
        val finishedAt = rs.getLong("finished_at")
        val finishedAtNull = rs.wasNull()
        val nextAllowedAt = rs.getLong("next_allowed_at")
        val nextAllowedAtNull = rs.wasNull()
        return DreamRunRecord(
            id = rs.getInt("id"),
            startedAt = rs.getLong("started_at"),
            finishedAt = if (finishedAtNull) null else finishedAt,
            mode = rs.getString("mode"),
            status = rs.getString("status"),
            inputNodeCount = rs.getInt("input_node_count"),
            mergedCount = rs.getInt("merged_count"),
            archivedCount = rs.getInt("archived_count"),
            tombstonedCount = rs.getInt("tombstoned_count"),
            createdDreamCount = rs.getInt("created_dream_count"),
            createdConsolidatedCount = rs.getInt("created_consolidated_count"),
            skippedCount = rs.getInt("skipped_count"),
            error = rs.getString("error"),
            dreamSummary = rs.getString("dream_summary"),
            dreamJournal = rs.getString("dream_journal"),
            dreamSymbols = parseStringList(rs.getString("dream_symbols")),
            dreamEmotions = parseStringList(rs.getString("dream_emotions")),
            nextAllowedAt = if (nextAllowedAtNull) null else nextAllowedAt
        )
    }

    fun insertMemoryNode(draft: MemoryNodeDraft): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memory_nodes (
                    uri, kind, content, normalized_text, searchable_text, keywords, aliases, entities, topics,
                    trigger_phrases, disclosure, priority, confidence, strength, emotion_valence, emotion_arousal,
                    scope_hint, person_uri, project_uri, created_at, updated_at, last_accessed_at, access_count,
                    status, source, raw_evidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, draft.uri)
                pstmt.setString(2, draft.kind)
                pstmt.setString(3, draft.content)
                pstmt.setString(4, draft.normalizedText)
                pstmt.setString(5, draft.searchableText)
                pstmt.setString(6, encodeStringList(draft.keywords))
                pstmt.setString(7, encodeStringList(draft.aliases))
                pstmt.setString(8, encodeStringList(draft.entities))
                pstmt.setString(9, encodeStringList(draft.topics))
                pstmt.setString(10, encodeStringList(draft.triggerPhrases))
                pstmt.setString(11, draft.disclosure)
                pstmt.setDouble(12, draft.priority)
                pstmt.setDouble(13, draft.confidence)
                pstmt.setDouble(14, draft.strength)
                pstmt.setDouble(15, draft.emotionValence)
                pstmt.setDouble(16, draft.emotionArousal)
                pstmt.setNullableString(17, draft.scopeHint)
                pstmt.setNullableString(18, draft.personUri)
                pstmt.setNullableString(19, draft.projectUri)
                pstmt.setLong(20, now)
                pstmt.setLong(21, now)
                pstmt.setLong(22, now)
                pstmt.setString(23, draft.status)
                pstmt.setString(24, draft.source)
                pstmt.setNullableString(25, draft.rawEvidence)
                pstmt.executeUpdate()
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    val nodeId = rs.getInt(1)
                    replaceMemorySearchTerms(nodeId, emptyList())
                    upsertMemoryNodeFts(nodeId, draft)
                    return nodeId
                }
            }
        }
        return -1
    }

    fun updateMemoryNode(nodeId: Int, draft: MemoryNodeDraft) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_nodes
                SET uri = ?, kind = ?, content = ?, normalized_text = ?, searchable_text = ?, keywords = ?,
                    aliases = ?, entities = ?, topics = ?, trigger_phrases = ?, disclosure = ?, priority = ?,
                    confidence = ?, strength = ?, emotion_valence = ?, emotion_arousal = ?, scope_hint = ?,
                    person_uri = ?, project_uri = ?, updated_at = ?, status = ?, source = ?, raw_evidence = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, draft.uri)
                pstmt.setString(2, draft.kind)
                pstmt.setString(3, draft.content)
                pstmt.setString(4, draft.normalizedText)
                pstmt.setString(5, draft.searchableText)
                pstmt.setString(6, encodeStringList(draft.keywords))
                pstmt.setString(7, encodeStringList(draft.aliases))
                pstmt.setString(8, encodeStringList(draft.entities))
                pstmt.setString(9, encodeStringList(draft.topics))
                pstmt.setString(10, encodeStringList(draft.triggerPhrases))
                pstmt.setString(11, draft.disclosure)
                pstmt.setDouble(12, draft.priority)
                pstmt.setDouble(13, draft.confidence)
                pstmt.setDouble(14, draft.strength)
                pstmt.setDouble(15, draft.emotionValence)
                pstmt.setDouble(16, draft.emotionArousal)
                pstmt.setNullableString(17, draft.scopeHint)
                pstmt.setNullableString(18, draft.personUri)
                pstmt.setNullableString(19, draft.projectUri)
                pstmt.setLong(20, now)
                pstmt.setString(21, draft.status)
                pstmt.setString(22, draft.source)
                pstmt.setNullableString(23, draft.rawEvidence)
                pstmt.setInt(24, nodeId)
                pstmt.executeUpdate()
            }
        }
        upsertMemoryNodeFts(nodeId, draft)
    }

    private fun upsertMemoryNodeFts(nodeId: Int, draft: MemoryNodeDraft) {
        getConnection().use { conn ->
            try {
                conn.prepareStatement("DELETE FROM memory_nodes_fts WHERE node_id = ?").use { delete ->
                    delete.setInt(1, nodeId)
                    delete.executeUpdate()
                }
                conn.prepareStatement("""
                    INSERT INTO memory_nodes_fts (
                        node_id, content, searchable_text, keywords, aliases, entities, topics, trigger_phrases
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { insert ->
                    insert.setInt(1, nodeId)
                    insert.setString(2, draft.content)
                    insert.setString(3, draft.searchableText)
                    insert.setString(4, draft.keywords.joinToString(" "))
                    insert.setString(5, draft.aliases.joinToString(" "))
                    insert.setString(6, draft.entities.joinToString(" "))
                    insert.setString(7, draft.topics.joinToString(" "))
                    insert.setString(8, draft.triggerPhrases.joinToString(" "))
                    insert.executeUpdate()
                }
            } catch (_: Exception) {
                // FTS table is optional.
            }
        }
    }

    fun replaceMemorySearchTerms(nodeId: Int, terms: List<MemorySearchTermDraft>) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM memory_search_terms WHERE node_id = ?").use { delete ->
                delete.setInt(1, nodeId)
                delete.executeUpdate()
            }
            if (terms.isEmpty()) return
            conn.prepareStatement("""
                INSERT INTO memory_search_terms (node_id, term, kind, weight)
                VALUES (?, ?, ?, ?)
            """.trimIndent()).use { insert ->
                for (term in terms.distinctBy { Pair(it.term, it.kind) }) {
                    insert.setInt(1, nodeId)
                    insert.setString(2, term.term)
                    insert.setString(3, term.kind)
                    insert.setDouble(4, term.weight)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
    }

    fun insertMemoryObservation(draft: MemoryObservationDraft): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memory_observations (
                    candidate_uri, kind, content, normalized_text, searchable_text, keywords, aliases, entities,
                    topics, trigger_phrases, person_uri, project_uri, scope_hint, priority, confidence,
                    emotion_valence, emotion_arousal, novelty, source, raw_evidence, status, matched_node_id,
                    seen_count, first_seen_at, last_seen_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'buffered', NULL, 1, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setNullableString(1, draft.candidateUri)
                pstmt.setString(2, draft.kind)
                pstmt.setString(3, draft.content)
                pstmt.setString(4, draft.normalizedText)
                pstmt.setString(5, draft.searchableText)
                pstmt.setString(6, encodeStringList(draft.keywords))
                pstmt.setString(7, encodeStringList(draft.aliases))
                pstmt.setString(8, encodeStringList(draft.entities))
                pstmt.setString(9, encodeStringList(draft.topics))
                pstmt.setString(10, encodeStringList(draft.triggerPhrases))
                pstmt.setNullableString(11, draft.personUri)
                pstmt.setNullableString(12, draft.projectUri)
                pstmt.setNullableString(13, draft.scopeHint)
                pstmt.setDouble(14, draft.priority)
                pstmt.setDouble(15, draft.confidence)
                pstmt.setDouble(16, draft.emotionValence)
                pstmt.setDouble(17, draft.emotionArousal)
                pstmt.setDouble(18, draft.novelty)
                pstmt.setString(19, draft.source)
                pstmt.setNullableString(20, draft.rawEvidence)
                pstmt.setLong(21, now)
                pstmt.setLong(22, now)
                pstmt.setLong(23, draft.expiresAt)
                pstmt.executeUpdate()
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    val observationId = rs.getInt(1)
                    upsertMemoryObservationFts(observationId, draft)
                    return observationId
                }
            }
        }
        return -1
    }

    fun updateMemoryObservationSeen(observationId: Int, draft: MemoryObservationDraft): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_observations
                SET content = ?, normalized_text = ?, searchable_text = ?, keywords = ?, aliases = ?,
                    entities = ?, topics = ?, trigger_phrases = ?, priority = MAX(priority, ?),
                    confidence = MAX(confidence, ?), emotion_valence = ?, emotion_arousal = MAX(emotion_arousal, ?),
                    novelty = MAX(novelty, ?), raw_evidence = COALESCE(?, raw_evidence),
                    seen_count = seen_count + 1, last_seen_at = ?, expires_at = MAX(expires_at, ?)
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, draft.content)
                pstmt.setString(2, draft.normalizedText)
                pstmt.setString(3, draft.searchableText)
                pstmt.setString(4, encodeStringList(draft.keywords))
                pstmt.setString(5, encodeStringList(draft.aliases))
                pstmt.setString(6, encodeStringList(draft.entities))
                pstmt.setString(7, encodeStringList(draft.topics))
                pstmt.setString(8, encodeStringList(draft.triggerPhrases))
                pstmt.setDouble(9, draft.priority)
                pstmt.setDouble(10, draft.confidence)
                pstmt.setDouble(11, draft.emotionValence)
                pstmt.setDouble(12, draft.emotionArousal)
                pstmt.setDouble(13, draft.novelty)
                pstmt.setNullableString(14, draft.rawEvidence)
                pstmt.setLong(15, now)
                pstmt.setLong(16, draft.expiresAt)
                pstmt.setInt(17, observationId)
                pstmt.executeUpdate()
            }
        }
        upsertMemoryObservationFts(observationId, draft)
        return getMemoryObservationById(observationId)?.seenCount ?: 1
    }

    private fun upsertMemoryObservationFts(observationId: Int, draft: MemoryObservationDraft) {
        getConnection().use { conn ->
            try {
                conn.prepareStatement("DELETE FROM memory_observations_fts WHERE observation_id = ?").use { delete ->
                    delete.setInt(1, observationId)
                    delete.executeUpdate()
                }
                conn.prepareStatement("""
                    INSERT INTO memory_observations_fts (
                        observation_id, content, searchable_text, keywords, aliases, entities, topics, trigger_phrases
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { insert ->
                    insert.setInt(1, observationId)
                    insert.setString(2, draft.content)
                    insert.setString(3, draft.searchableText)
                    insert.setString(4, draft.keywords.joinToString(" "))
                    insert.setString(5, draft.aliases.joinToString(" "))
                    insert.setString(6, draft.entities.joinToString(" "))
                    insert.setString(7, draft.topics.joinToString(" "))
                    insert.setString(8, draft.triggerPhrases.joinToString(" "))
                    insert.executeUpdate()
                }
            } catch (_: Exception) {
                // FTS table is optional.
            }
        }
    }

    fun replaceMemoryObservationTerms(observationId: Int, terms: List<MemorySearchTermDraft>) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM memory_observation_terms WHERE observation_id = ?").use { delete ->
                delete.setInt(1, observationId)
                delete.executeUpdate()
            }
            if (terms.isEmpty()) return
            conn.prepareStatement("""
                INSERT INTO memory_observation_terms (observation_id, term, kind, weight)
                VALUES (?, ?, ?, ?)
            """.trimIndent()).use { insert ->
                for (term in terms.distinctBy { Pair(it.term, it.kind) }) {
                    insert.setInt(1, observationId)
                    insert.setString(2, term.term)
                    insert.setString(3, term.kind)
                    insert.setDouble(4, term.weight)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
    }

    fun getMemoryObservationById(id: Int): MemoryObservationRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM memory_observations WHERE id = ? LIMIT 1").use { pstmt ->
                pstmt.setInt(1, id)
                val rs = pstmt.executeQuery()
                if (rs.next()) return readMemoryObservation(rs)
            }
        }
        return null
    }

    fun getRecentBufferedObservations(kind: String, personUri: String?, projectUri: String?, limit: Int): List<MemoryObservationRecord> {
        val list = mutableListOf<MemoryObservationRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_observations
                WHERE status = 'buffered'
                  AND kind = ?
                  AND (? IS NULL OR person_uri = ?)
                  AND (? IS NULL OR project_uri = ?)
                ORDER BY last_seen_at DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, kind)
                pstmt.setNullableString(2, personUri)
                pstmt.setNullableString(3, personUri)
                pstmt.setNullableString(4, projectUri)
                pstmt.setNullableString(5, projectUri)
                pstmt.setInt(6, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryObservation(rs))
                }
            }
        }
        return list
    }

    fun updateMemoryObservationStatus(observationId: Int, status: String, matchedNodeId: Int? = null) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_observations
                SET status = ?, matched_node_id = COALESCE(?, matched_node_id), last_seen_at = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, status)
                if (matchedNodeId != null) pstmt.setInt(2, matchedNodeId) else pstmt.setNull(2, java.sql.Types.INTEGER)
                pstmt.setLong(3, Instant.now().epochSecond)
                pstmt.setInt(4, observationId)
                pstmt.executeUpdate()
            }
        }
    }

    fun expireMemoryObservations(now: Long = Instant.now().epochSecond): Int {
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_observations
                SET status = 'expired'
                WHERE status = 'buffered' AND expires_at < ?
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, now)
                return pstmt.executeUpdate()
            }
        }
    }

    fun getBufferedObservationCount(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM memory_observations WHERE status = 'buffered'").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun getObservationCountSince(status: String? = null, sinceEpochSecond: Long): Int {
        getConnection().use { conn ->
            val sql = if (status == null) {
                "SELECT COUNT(*) FROM memory_observations WHERE first_seen_at >= ?"
            } else {
                "SELECT COUNT(*) FROM memory_observations WHERE status = ? AND last_seen_at >= ?"
            }
            conn.prepareStatement(sql).use { pstmt ->
                if (status == null) {
                    pstmt.setLong(1, sinceEpochSecond)
                } else {
                    pstmt.setString(1, status)
                    pstmt.setLong(2, sinceEpochSecond)
                }
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun insertDreamRun(draft: DreamRunDraft): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO dream_runs (
                    started_at, finished_at, mode, status, input_node_count, merged_count, archived_count,
                    tombstoned_count, created_dream_count, created_consolidated_count, skipped_count, error,
                    dream_summary, dream_journal, dream_symbols, dream_emotions, next_allowed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, now)
                if (draft.status in setOf("completed", "failed", "skipped")) pstmt.setLong(2, now) else pstmt.setNull(2, java.sql.Types.INTEGER)
                pstmt.setString(3, draft.mode)
                pstmt.setString(4, draft.status)
                pstmt.setInt(5, draft.inputNodeCount)
                pstmt.setInt(6, draft.mergedCount)
                pstmt.setInt(7, draft.archivedCount)
                pstmt.setInt(8, draft.tombstonedCount)
                pstmt.setInt(9, draft.createdDreamCount)
                pstmt.setInt(10, draft.createdConsolidatedCount)
                pstmt.setInt(11, draft.skippedCount)
                pstmt.setNullableString(12, draft.error)
                pstmt.setNullableString(13, draft.dreamSummary)
                pstmt.setNullableString(14, draft.dreamJournal)
                pstmt.setString(15, encodeStringList(draft.dreamSymbols))
                pstmt.setString(16, encodeStringList(draft.dreamEmotions))
                if (draft.nextAllowedAt != null) pstmt.setLong(17, draft.nextAllowedAt) else pstmt.setNull(17, java.sql.Types.INTEGER)
                pstmt.executeUpdate()
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return -1
    }

    fun updateDreamRun(dreamRunId: Int, draft: DreamRunDraft) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE dream_runs
                SET finished_at = ?, status = ?, input_node_count = ?, merged_count = ?, archived_count = ?,
                    tombstoned_count = ?, created_dream_count = ?, created_consolidated_count = ?,
                    skipped_count = ?, error = ?, dream_summary = ?, dream_journal = ?,
                    dream_symbols = ?, dream_emotions = ?, next_allowed_at = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                if (draft.status in setOf("completed", "failed", "skipped")) pstmt.setLong(1, now) else pstmt.setNull(1, java.sql.Types.INTEGER)
                pstmt.setString(2, draft.status)
                pstmt.setInt(3, draft.inputNodeCount)
                pstmt.setInt(4, draft.mergedCount)
                pstmt.setInt(5, draft.archivedCount)
                pstmt.setInt(6, draft.tombstonedCount)
                pstmt.setInt(7, draft.createdDreamCount)
                pstmt.setInt(8, draft.createdConsolidatedCount)
                pstmt.setInt(9, draft.skippedCount)
                pstmt.setNullableString(10, draft.error)
                pstmt.setNullableString(11, draft.dreamSummary)
                pstmt.setNullableString(12, draft.dreamJournal)
                pstmt.setString(13, encodeStringList(draft.dreamSymbols))
                pstmt.setString(14, encodeStringList(draft.dreamEmotions))
                if (draft.nextAllowedAt != null) pstmt.setLong(15, draft.nextAllowedAt) else pstmt.setNull(15, java.sql.Types.INTEGER)
                pstmt.setInt(16, dreamRunId)
                pstmt.executeUpdate()
            }
        }
    }

    fun insertDreamRunItem(
        dreamRunId: Int,
        nodeId: Int?,
        nodeUri: String?,
        operation: String,
        reason: String?,
        result: String,
        targetNodeId: Int? = null,
        targetUri: String? = null
    ) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO dream_run_items (
                    dream_run_id, node_id, node_uri, operation, reason, result, target_node_id, target_uri, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, dreamRunId)
                if (nodeId != null) pstmt.setInt(2, nodeId) else pstmt.setNull(2, java.sql.Types.INTEGER)
                pstmt.setNullableString(3, nodeUri)
                pstmt.setString(4, operation)
                pstmt.setNullableString(5, reason)
                pstmt.setString(6, result)
                if (targetNodeId != null) pstmt.setInt(7, targetNodeId) else pstmt.setNull(7, java.sql.Types.INTEGER)
                pstmt.setNullableString(8, targetUri)
                pstmt.setLong(9, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun getLatestDreamRun(mode: String? = null): DreamRunRecord? {
        getConnection().use { conn ->
            val sql = if (mode == null) {
                "SELECT * FROM dream_runs ORDER BY started_at DESC, id DESC LIMIT 1"
            } else {
                "SELECT * FROM dream_runs WHERE mode = ? ORDER BY started_at DESC, id DESC LIMIT 1"
            }
            conn.prepareStatement(sql).use { pstmt ->
                if (mode != null) pstmt.setString(1, mode)
                val rs = pstmt.executeQuery()
                if (rs.next()) return readDreamRun(rs)
            }
        }
        return null
    }

    fun countDreamRunsSince(mode: String, sinceEpochSecond: Long): Int {
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT COUNT(*)
                FROM dream_runs
                WHERE mode = ? AND started_at >= ? AND status IN ('completed', 'failed')
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, mode)
                pstmt.setLong(2, sinceEpochSecond)
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun archiveMemoryNodeToRecycle(node: MemoryNodeRecord, reason: String, retentionDays: Int): Boolean {
        val now = Instant.now().epochSecond
        val purgeAfter = now + retentionDays.coerceAtLeast(1) * 86400L
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("""
                    INSERT INTO memory_recycle_bin (node_id, uri, content, raw_evidence, keywords, topics, reason, created_at, purge_after)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { pstmt ->
                    pstmt.setInt(1, node.id)
                    pstmt.setString(2, node.uri)
                    pstmt.setString(3, node.content)
                    pstmt.setNullableString(4, node.rawEvidence)
                    pstmt.setString(5, encodeStringList(node.keywords))
                    pstmt.setString(6, encodeStringList(node.topics))
                    pstmt.setNullableString(7, reason)
                    pstmt.setLong(8, now)
                    pstmt.setLong(9, purgeAfter)
                    pstmt.executeUpdate()
                }
                conn.prepareStatement("UPDATE memory_nodes SET status = 'archived', updated_at = ? WHERE id = ? AND status = 'active'").use { pstmt ->
                    pstmt.setLong(1, now)
                    pstmt.setInt(2, node.id)
                    pstmt.executeUpdate()
                }
                conn.commit()
                return true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun compressExpiredRecycleBin(now: Long = Instant.now().epochSecond): Int {
        val expired = mutableListOf<Int>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT node_id FROM memory_recycle_bin WHERE purge_after <= ?").use { pstmt ->
                pstmt.setLong(1, now)
                val rs = pstmt.executeQuery()
                while (rs.next()) expired.add(rs.getInt("node_id"))
            }
        }
        if (expired.isEmpty()) return 0

        getConnection().use { conn ->
            val placeholders = expired.joinToString(",") { "?" }
            conn.prepareStatement("""
                UPDATE memory_nodes
                SET status = 'tombstone',
                    content = '[tombstone] ' || uri,
                    normalized_text = LOWER(uri),
                    searchable_text = uri,
                    keywords = '[]',
                    aliases = '[]',
                    entities = '[]',
                    topics = '[]',
                    trigger_phrases = '[]',
                    raw_evidence = NULL,
                    updated_at = ?
                WHERE id IN ($placeholders)
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, now)
                expired.forEachIndexed { index, id -> pstmt.setInt(index + 2, id) }
                val count = pstmt.executeUpdate()
                conn.prepareStatement("DELETE FROM memory_recycle_bin WHERE purge_after <= ?").use { delete ->
                    delete.setLong(1, now)
                    delete.executeUpdate()
                }
                return count
            }
        }
    }

    fun upsertMemoryEdge(draft: MemoryEdgeDraft) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memory_edges (from_node_id, to_node_id, relation, weight, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(from_node_id, to_node_id, relation) DO UPDATE SET
                    weight = excluded.weight,
                    updated_at = excluded.updated_at
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, draft.fromNodeId)
                pstmt.setInt(2, draft.toNodeId)
                pstmt.setString(3, draft.relation)
                pstmt.setDouble(4, draft.weight)
                pstmt.setLong(5, now)
                pstmt.setLong(6, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun getMemoryNodeByUri(uri: String): MemoryNodeRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM memory_nodes WHERE uri = ? LIMIT 1").use { pstmt ->
                pstmt.setString(1, uri)
                val rs = pstmt.executeQuery()
                if (rs.next()) return readMemoryNode(rs)
            }
        }
        return null
    }

    fun getMemoryNodeById(id: Int): MemoryNodeRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM memory_nodes WHERE id = ? LIMIT 1").use { pstmt ->
                pstmt.setInt(1, id)
                val rs = pstmt.executeQuery()
                if (rs.next()) return readMemoryNode(rs)
            }
        }
        return null
    }

    fun getAllMemoryNodes(): List<MemoryNodeRecord> {
        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM memory_nodes WHERE status = 'active' ORDER BY updated_at DESC, id DESC").use { pstmt ->
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryNode(rs))
                }
            }
        }
        return list
    }

    fun getMemoryNodesByIds(ids: Collection<Int>): List<MemoryNodeRecord> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM memory_nodes WHERE id IN ($placeholders)").use { pstmt ->
                ids.forEachIndexed { index, id -> pstmt.setInt(index + 1, id) }
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryNode(rs))
                }
            }
        }
        return list
    }

    fun searchMemoryNodes(terms: List<String>, limit: Int): List<MemoryNodeRecord> {
        if (terms.isEmpty() || limit <= 0) return emptyList()
        val ids = LinkedHashSet<Int>()
        val normalizedTerms = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedTerms.isEmpty()) return emptyList()

        getConnection().use { conn ->
            try {
                val matchQuery = normalizedTerms.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
                conn.prepareStatement("""
                    SELECT node_id
                    FROM memory_nodes_fts
                    WHERE memory_nodes_fts MATCH ?
                    LIMIT ?
                """.trimIndent()).use { pstmt ->
                    pstmt.setString(1, matchQuery)
                    pstmt.setInt(2, limit)
                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        ids.add(rs.getInt("node_id"))
                    }
                }
            } catch (_: Exception) {
                // FTS may not exist or the query may be too loose. Fall back below.
            }

            if (ids.size < limit) {
                val placeholders = normalizedTerms.joinToString(",") { "?" }
                conn.prepareStatement("""
                    SELECT DISTINCT node_id
                    FROM memory_search_terms
                    WHERE term IN ($placeholders)
                    ORDER BY weight DESC
                    LIMIT ?
                """.trimIndent()).use { pstmt ->
                    normalizedTerms.forEachIndexed { index, term -> pstmt.setString(index + 1, term) }
                    pstmt.setInt(normalizedTerms.size + 1, limit)
                    val rs = pstmt.executeQuery()
                    while (rs.next() && ids.size < limit) {
                        ids.add(rs.getInt("node_id"))
                    }
                }
            }

            if (ids.size < limit) {
                val where = normalizedTerms.joinToString(" OR ") {
                    "(LOWER(searchable_text) LIKE ? OR LOWER(content) LIKE ? OR LOWER(uri) LIKE ?)"
                }
                conn.prepareStatement("""
                    SELECT id
                    FROM memory_nodes
                    WHERE $where
                    ORDER BY updated_at DESC
                    LIMIT ?
                """.trimIndent()).use { pstmt ->
                    var position = 1
                    normalizedTerms.forEach { term ->
                        val like = "%$term%"
                        pstmt.setString(position++, like)
                        pstmt.setString(position++, like)
                        pstmt.setString(position++, like)
                    }
                    pstmt.setInt(position, limit)
                    val rs = pstmt.executeQuery()
                    while (rs.next() && ids.size < limit) {
                        ids.add(rs.getInt("id"))
                    }
                }
            }
        }

        val byId = getMemoryNodesByIds(ids).filter { it.status == "active" }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }.take(limit)
    }

    fun getRecentGraphMemoryNodes(limit: Int, kinds: Set<String> = emptySet()): List<MemoryNodeRecord> {
        val list = mutableListOf<MemoryNodeRecord>()
        val sql = if (kinds.isEmpty()) {
            "SELECT * FROM memory_nodes WHERE status = 'active' ORDER BY updated_at DESC LIMIT ?"
        } else {
            val placeholders = kinds.joinToString(",") { "?" }
            "SELECT * FROM memory_nodes WHERE status = 'active' AND kind IN ($placeholders) ORDER BY updated_at DESC LIMIT ?"
        }
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var position = 1
                kinds.forEach { pstmt.setString(position++, it) }
                pstmt.setInt(position, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryNode(rs))
                }
            }
        }
        return list
    }

    fun getEdgesForNodeIds(nodeIds: Collection<Int>, relations: Set<String> = emptySet(), limit: Int = 100): List<MemoryEdgeRecord> {
        if (nodeIds.isEmpty() || limit <= 0) return emptyList()
        val idsPlaceholders = nodeIds.joinToString(",") { "?" }
        val relationClause = if (relations.isEmpty()) {
            ""
        } else {
            " AND relation IN (${relations.joinToString(",") { "?" }})"
        }
        val sql = """
            SELECT *
            FROM memory_edges
            WHERE (from_node_id IN ($idsPlaceholders) OR to_node_id IN ($idsPlaceholders))$relationClause
            ORDER BY weight DESC, updated_at DESC
            LIMIT ?
        """.trimIndent()

        val list = mutableListOf<MemoryEdgeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var position = 1
                nodeIds.forEach { pstmt.setInt(position++, it) }
                nodeIds.forEach { pstmt.setInt(position++, it) }
                relations.forEach { pstmt.setString(position++, it) }
                pstmt.setInt(position, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryEdge(rs))
                }
            }
        }
        return list
    }

    fun updateMemoryNodeAccess(nodeId: Int, strengthDelta: Double, maxStrength: Double) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_nodes
                SET strength = MIN(strength + ?, ?),
                    last_accessed_at = ?,
                    access_count = access_count + 1
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, strengthDelta)
                pstmt.setDouble(2, maxStrength)
                pstmt.setLong(3, now)
                pstmt.setInt(4, nodeId)
                pstmt.executeUpdate()
            }
        }
    }

    fun decayGraphMemoryNodes(threshold: Double) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM memory_nodes WHERE status = 'active' AND strength < ?").use { pstmt ->
                pstmt.setDouble(1, threshold)
                pstmt.executeUpdate()
            }
            try {
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        DELETE FROM memory_nodes_fts
                        WHERE node_id NOT IN (SELECT id FROM memory_nodes)
                    """.trimIndent())
                }
            } catch (_: Exception) {
                // Optional FTS table.
            }
        }
    }

    fun listMemoryNodes(
        q: String?,
        uriPrefix: String?,
        kind: String?,
        disclosure: String?,
        status: String?,
        limit: Int
    ): List<MemoryNodeRecord> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (!uriPrefix.isNullOrBlank()) {
            clauses.add("uri LIKE ?")
            params.add("${uriPrefix.trim()}%")
        }
        if (!kind.isNullOrBlank()) {
            clauses.add("kind = ?")
            params.add(kind.trim())
        }
        if (!disclosure.isNullOrBlank()) {
            clauses.add("disclosure = ?")
            params.add(disclosure.trim())
        }
        if (!status.isNullOrBlank()) {
            clauses.add("status = ?")
            params.add(status.trim())
        }
        if (!q.isNullOrBlank()) {
            clauses.add("(LOWER(content) LIKE ? OR LOWER(searchable_text) LIKE ? OR LOWER(uri) LIKE ?)")
            repeat(3) { params.add("%${q.trim().lowercase()}%") }
        }

        val sql = buildString {
            append("SELECT * FROM memory_nodes")
            if (clauses.isNotEmpty()) {
                append(" WHERE ")
                append(clauses.joinToString(" AND "))
            }
            append(" ORDER BY updated_at DESC, strength DESC LIMIT ?")
        }

        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                params.forEachIndexed { index, value -> pstmt.setString(index + 1, value) }
                pstmt.setInt(params.size + 1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryNode(rs))
                }
            }
        }
        return list
    }

    fun getGraphNodeCount(status: String? = null): Int {
        getConnection().use { conn ->
            val sql = if (status == null) "SELECT COUNT(*) FROM memory_nodes" else "SELECT COUNT(*) FROM memory_nodes WHERE status = ?"
            conn.prepareStatement(sql).use { pstmt ->
                if (status != null) pstmt.setString(1, status)
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun getGraphEdgeCount(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM memory_edges").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun getSearchTermCount(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM memory_search_terms").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun getWorkingMemoryCount(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM memory_nodes WHERE status = 'active' AND kind = 'working_memory'").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun getGraphAffectDistribution(): JsonObject {
        val buckets = mutableMapOf(
            "positive_calm" to 0, "positive_intense" to 0,
            "negative_calm" to 0, "negative_intense" to 0,
            "neutral" to 0
        )
        getConnection().use { conn ->
            conn.prepareStatement("SELECT emotion_valence, emotion_arousal FROM memory_nodes WHERE status = 'active'").use { pstmt ->
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
