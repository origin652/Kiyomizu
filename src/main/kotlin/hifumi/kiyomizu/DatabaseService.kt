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

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS self_memory_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_type TEXT NOT NULL,
                        node_id INTEGER,
                        node_uri TEXT,
                        observation_id INTEGER,
                        previous_node_id INTEGER,
                        previous_node_uri TEXT,
                        new_node_id INTEGER,
                        new_node_uri TEXT,
                        source TEXT NOT NULL,
                        reason TEXT,
                        content_before TEXT,
                        content_after TEXT,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_index_segments (
                        segment_key TEXT PRIMARY KEY,
                        content TEXT NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        dirty INTEGER NOT NULL DEFAULT 0,
                        error TEXT,
                        char_count INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_model_recall_traces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL,
                        query TEXT NOT NULL,
                        index_version TEXT NOT NULL,
                        plan_json TEXT,
                        candidate_count INTEGER NOT NULL DEFAULT 0,
                        injected_count INTEGER NOT NULL DEFAULT 0,
                        filtered_summary TEXT,
                        fallback_reason TEXT,
                        error TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        debug_json TEXT
                    )
                """.trimIndent())
                ensureModelRecallTracesSchema(conn)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS memory_term_edges (
                        term_a TEXT NOT NULL,
                        term_b TEXT NOT NULL,
                        co_count INTEGER NOT NULL DEFAULT 1,
                        weight REAL NOT NULL DEFAULT 1.0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(term_a, term_b)
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
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_self_memory_events_created ON self_memory_events(created_at DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_self_memory_events_node ON self_memory_events(node_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_model_recall_traces_created ON memory_model_recall_traces(created_at DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_term_edges_a ON memory_term_edges(term_a, weight DESC)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_term_edges_b ON memory_term_edges(term_b, weight DESC)")

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
                ensureRequestLogsSchema(conn)

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

    private fun ensureModelRecallTracesSchema(conn: Connection) {
        val columns = mutableSetOf<String>()
        conn.prepareStatement("PRAGMA table_info(memory_model_recall_traces)").use { pstmt ->
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }
        if ("debug_json" !in columns) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE memory_model_recall_traces ADD COLUMN debug_json TEXT")
            }
        }
    }

    private fun ensureRequestLogsSchema(conn: Connection) {
        val columns = mutableSetOf<String>()
        conn.prepareStatement("PRAGMA table_info(request_logs)").use { pstmt ->
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }

        val additions = listOf(
            "cache_mode" to "TEXT",
            "cache_strategy" to "TEXT",
            "cache_breakpoints" to "INTEGER",
            "cache_breakpoint_indexes" to "TEXT",
            "patch_eligible" to "INTEGER",
            "input_tokens" to "INTEGER",
            "output_tokens" to "INTEGER",
            "cache_read_input_tokens" to "INTEGER",
            "cache_creation_input_tokens" to "INTEGER",
            "cached_prompt_tokens" to "INTEGER",
            "usage_json" to "TEXT"
        )
        conn.createStatement().use { stmt ->
            additions.forEach { (name, type) ->
                if (name !in columns) {
                    stmt.execute("ALTER TABLE request_logs ADD COLUMN $name $type")
                }
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

    data class MemoryNodeSearchHit(
        val node: MemoryNodeRecord,
        val textScore: Double,
        val matchReason: String,
        val matchedTerms: List<String>
    )

    data class MemoryTermEdgeRecord(
        val termA: String,
        val termB: String,
        val coCount: Int,
        val weight: Double,
        val updatedAt: Long
    )

    data class MemoryTermGraphStats(
        val termCount: Int,
        val edgeCount: Int,
        val updatedAt: Long,
        val dirty: Boolean,
        val error: String?
    )

    data class MemoryIndexSegmentRecord(
        val segmentKey: String,
        val content: String,
        val version: Int,
        val dirty: Boolean,
        val error: String?,
        val charCount: Int,
        val updatedAt: Long
    )

    data class ModelRecallTraceRecord(
        val id: Int,
        val createdAt: Long,
        val query: String,
        val indexVersion: String,
        val planJson: String?,
        val candidateCount: Int,
        val injectedCount: Int,
        val filteredSummary: String?,
        val fallbackReason: String?,
        val error: String?,
        val durationMs: Int,
        val debugJson: String?
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

    data class DreamRunItemRecord(
        val id: Int,
        val dreamRunId: Int,
        val nodeId: Int?,
        val nodeUri: String?,
        val operation: String,
        val reason: String?,
        val result: String,
        val targetNodeId: Int?,
        val targetUri: String?,
        val createdAt: Long
    )

    data class RecycleBinRecord(
        val id: Int,
        val nodeId: Int,
        val uri: String,
        val content: String?,
        val rawEvidence: String?,
        val keywords: List<String>,
        val topics: List<String>,
        val reason: String?,
        val createdAt: Long,
        val purgeAfter: Long
    )

    data class SelfMemoryEventRecord(
        val id: Int,
        val eventType: String,
        val nodeId: Int?,
        val nodeUri: String?,
        val observationId: Int?,
        val previousNodeId: Int?,
        val previousNodeUri: String?,
        val newNodeId: Int?,
        val newNodeUri: String?,
        val source: String,
        val reason: String?,
        val contentBefore: String?,
        val contentAfter: String?,
        val createdAt: Long
    )

    data class SelfMemoryEventDraft(
        val eventType: String,
        val nodeId: Int? = null,
        val nodeUri: String? = null,
        val observationId: Int? = null,
        val previousNodeId: Int? = null,
        val previousNodeUri: String? = null,
        val newNodeId: Int? = null,
        val newNodeUri: String? = null,
        val source: String,
        val reason: String? = null,
        val contentBefore: String? = null,
        val contentAfter: String? = null
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

    private fun readRecycleBinRecord(rs: java.sql.ResultSet): RecycleBinRecord {
        return RecycleBinRecord(
            id = rs.getInt("id"),
            nodeId = rs.getInt("node_id"),
            uri = rs.getString("uri"),
            content = rs.getString("content"),
            rawEvidence = rs.getString("raw_evidence"),
            keywords = parseStringList(rs.getString("keywords")),
            topics = parseStringList(rs.getString("topics")),
            reason = rs.getString("reason"),
            createdAt = rs.getLong("created_at"),
            purgeAfter = rs.getLong("purge_after")
        )
    }

    private fun readDreamRunItem(rs: java.sql.ResultSet): DreamRunItemRecord {
        val nodeId = rs.getInt("node_id")
        val nodeIdNull = rs.wasNull()
        val targetNodeId = rs.getInt("target_node_id")
        val targetNodeIdNull = rs.wasNull()
        return DreamRunItemRecord(
            id = rs.getInt("id"),
            dreamRunId = rs.getInt("dream_run_id"),
            nodeId = if (nodeIdNull) null else nodeId,
            nodeUri = rs.getString("node_uri"),
            operation = rs.getString("operation"),
            reason = rs.getString("reason"),
            result = rs.getString("result"),
            targetNodeId = if (targetNodeIdNull) null else targetNodeId,
            targetUri = rs.getString("target_uri"),
            createdAt = rs.getLong("created_at")
        )
    }

    private fun readSelfMemoryEvent(rs: java.sql.ResultSet): SelfMemoryEventRecord {
        fun nullableInt(column: String): Int? {
            val value = rs.getInt(column)
            return if (rs.wasNull()) null else value
        }
        return SelfMemoryEventRecord(
            id = rs.getInt("id"),
            eventType = rs.getString("event_type"),
            nodeId = nullableInt("node_id"),
            nodeUri = rs.getString("node_uri"),
            observationId = nullableInt("observation_id"),
            previousNodeId = nullableInt("previous_node_id"),
            previousNodeUri = rs.getString("previous_node_uri"),
            newNodeId = nullableInt("new_node_id"),
            newNodeUri = rs.getString("new_node_uri"),
            source = rs.getString("source"),
            reason = rs.getString("reason"),
            contentBefore = rs.getString("content_before"),
            contentAfter = rs.getString("content_after"),
            createdAt = rs.getLong("created_at")
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

    fun setMemoryNodeStatus(nodeId: Int, status: String) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_nodes
                SET status = ?, updated_at = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, status)
                pstmt.setLong(2, Instant.now().epochSecond)
                pstmt.setInt(3, nodeId)
                pstmt.executeUpdate()
            }
            if (status == "active") {
                conn.prepareStatement("DELETE FROM memory_recycle_bin WHERE node_id = ?").use { pstmt ->
                    pstmt.setInt(1, nodeId)
                    pstmt.executeUpdate()
                }
            }
        }
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

    fun getBufferedObservationsForMaintenance(limit: Int): List<MemoryObservationRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<MemoryObservationRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_observations
                WHERE status = 'buffered'
                ORDER BY seen_count DESC, priority DESC, confidence DESC, last_seen_at DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryObservation(rs))
                }
            }
        }
        return list
    }

    fun listMemoryObservations(
        q: String?,
        status: String?,
        kind: String?,
        limit: Int
    ): List<MemoryObservationRecord> {
        if (limit <= 0) return emptyList()
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (!status.isNullOrBlank()) {
            clauses.add("status = ?")
            params.add(status.trim())
        }
        if (!kind.isNullOrBlank()) {
            clauses.add("kind = ?")
            params.add(kind.trim())
        }
        if (!q.isNullOrBlank()) {
            clauses.add("(LOWER(content) LIKE ? OR LOWER(searchable_text) LIKE ? OR LOWER(COALESCE(candidate_uri, '')) LIKE ?)")
            repeat(3) { params.add("%${q.trim().lowercase()}%") }
        }

        val sql = buildString {
            append("SELECT * FROM memory_observations")
            if (clauses.isNotEmpty()) {
                append(" WHERE ")
                append(clauses.joinToString(" AND "))
            }
            append(" ORDER BY last_seen_at DESC, id DESC LIMIT ?")
        }

        val list = mutableListOf<MemoryObservationRecord>()
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                params.forEachIndexed { index, value -> pstmt.setString(index + 1, value) }
                pstmt.setInt(params.size + 1, limit)
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
                WHERE mode = ? AND started_at >= ? AND status IN ('completed', 'failed', 'skipped')
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, mode)
                pstmt.setLong(2, sinceEpochSecond)
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun getDreamRunItems(dreamRunId: Int, limit: Int = 100): List<DreamRunItemRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<DreamRunItemRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM dream_run_items
                WHERE dream_run_id = ?
                ORDER BY id ASC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, dreamRunId)
                pstmt.setInt(2, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readDreamRunItem(rs))
                }
            }
        }
        return list
    }

    fun listRecycleBin(limit: Int): List<RecycleBinRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<RecycleBinRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_recycle_bin
                ORDER BY purge_after ASC, id DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readRecycleBinRecord(rs))
                }
            }
        }
        return list
    }

    fun insertSelfMemoryEvent(draft: SelfMemoryEventDraft): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO self_memory_events (
                    event_type, node_id, node_uri, observation_id, previous_node_id, previous_node_uri,
                    new_node_id, new_node_uri, source, reason, content_before, content_after, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, draft.eventType)
                if (draft.nodeId != null) pstmt.setInt(2, draft.nodeId) else pstmt.setNull(2, java.sql.Types.INTEGER)
                pstmt.setNullableString(3, draft.nodeUri)
                if (draft.observationId != null) pstmt.setInt(4, draft.observationId) else pstmt.setNull(4, java.sql.Types.INTEGER)
                if (draft.previousNodeId != null) pstmt.setInt(5, draft.previousNodeId) else pstmt.setNull(5, java.sql.Types.INTEGER)
                pstmt.setNullableString(6, draft.previousNodeUri)
                if (draft.newNodeId != null) pstmt.setInt(7, draft.newNodeId) else pstmt.setNull(7, java.sql.Types.INTEGER)
                pstmt.setNullableString(8, draft.newNodeUri)
                pstmt.setString(9, draft.source)
                pstmt.setNullableString(10, draft.reason)
                pstmt.setNullableString(11, draft.contentBefore)
                pstmt.setNullableString(12, draft.contentAfter)
                pstmt.setLong(13, now)
                pstmt.executeUpdate()
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return -1
    }

    fun getSelfMemoryEventById(id: Int): SelfMemoryEventRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM self_memory_events WHERE id = ? LIMIT 1").use { pstmt ->
                pstmt.setInt(1, id)
                val rs = pstmt.executeQuery()
                if (rs.next()) return readSelfMemoryEvent(rs)
            }
        }
        return null
    }

    fun listSelfMemoryEvents(limit: Int): List<SelfMemoryEventRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<SelfMemoryEventRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM self_memory_events
                ORDER BY created_at DESC, id DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readSelfMemoryEvent(rs))
            }
        }
        return list
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
            conn.autoCommit = false
            try {
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
                conn.prepareStatement("DELETE FROM memory_search_terms WHERE node_id IN ($placeholders)").use { deleteTerms ->
                    expired.forEachIndexed { index, id -> deleteTerms.setInt(index + 1, id) }
                    deleteTerms.executeUpdate()
                }
                try {
                    conn.prepareStatement("DELETE FROM memory_nodes_fts WHERE node_id IN ($placeholders)").use { deleteFts ->
                        expired.forEachIndexed { index, id -> deleteFts.setInt(index + 1, id) }
                        deleteFts.executeUpdate()
                    }
                } catch (_: Exception) {
                    // FTS table is optional.
                }
                conn.prepareStatement("DELETE FROM memory_recycle_bin WHERE purge_after <= ?").use { delete ->
                    delete.setLong(1, now)
                    delete.executeUpdate()
                }
                conn.commit()
                return count
            }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
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

    fun searchMemoryNodeHits(
        queryTerms: List<String>,
        expandedTerms: List<String>,
        statuses: Set<String> = setOf("active"),
        limit: Int
    ): List<MemoryNodeSearchHit> {
        if (limit <= 0) return emptyList()
        val primaryTerms = queryTerms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val secondaryTerms = expandedTerms.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it !in primaryTerms }.distinct()
        val allTerms = (primaryTerms + secondaryTerms).distinct()
        if (allTerms.isEmpty()) return emptyList()
        val allowedStatuses = statuses.ifEmpty { setOf("active") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (allowedStatuses.isEmpty()) return emptyList()

        data class HitAccumulator(
            var score: Double,
            val reasons: MutableSet<String>
        )

        val hits = linkedMapOf<Int, HitAccumulator>()
        fun addHit(id: Int, score: Double, reason: String) {
            val existing = hits[id]
            if (existing == null) {
                hits[id] = HitAccumulator(score, linkedSetOf(reason))
            } else {
                existing.score = maxOf(existing.score, score)
                existing.reasons += reason
            }
        }

        getConnection().use { conn ->
            val statusPlaceholders = allowedStatuses.joinToString(",") { "?" }
            try {
                val matchQuery = allTerms.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
                conn.prepareStatement("""
                    SELECT memory_nodes_fts.node_id AS node_id, bm25(memory_nodes_fts) AS rank
                    FROM memory_nodes_fts
                    JOIN memory_nodes ON memory_nodes.id = memory_nodes_fts.node_id
                    WHERE memory_nodes_fts MATCH ?
                      AND memory_nodes.status IN ($statusPlaceholders)
                    ORDER BY rank ASC, memory_nodes.updated_at DESC, memory_nodes.uri ASC
                    LIMIT ?
                """.trimIndent()).use { pstmt ->
                    pstmt.setString(1, matchQuery)
                    allowedStatuses.forEachIndexed { index, status -> pstmt.setString(index + 2, status) }
                    pstmt.setInt(allowedStatuses.size + 2, limit * 2)
                    val rs = pstmt.executeQuery()
                    var rank = 0
                    while (rs.next()) {
                        rank += 1
                        addHit(rs.getInt("node_id"), 3.0 + (limit * 2 - rank).coerceAtLeast(0) / limit.toDouble(), "fts_bm25")
                    }
                }
            } catch (_: Exception) {
                // FTS5 is optional. Term and LIKE search below keep recall functional.
            }

            val termPlaceholders = allTerms.joinToString(",") { "?" }
            conn.prepareStatement("""
                SELECT memory_search_terms.node_id AS node_id,
                       SUM(memory_search_terms.weight) AS score
                FROM memory_search_terms
                JOIN memory_nodes ON memory_nodes.id = memory_search_terms.node_id
                WHERE memory_search_terms.term IN ($termPlaceholders)
                  AND memory_nodes.status IN ($statusPlaceholders)
                GROUP BY memory_search_terms.node_id
                ORDER BY score DESC, memory_nodes.updated_at DESC, memory_nodes.uri ASC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                var position = 1
                allTerms.forEach { term -> pstmt.setString(position++, term) }
                allowedStatuses.forEach { status -> pstmt.setString(position++, status) }
                pstmt.setInt(position, limit * 3)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    addHit(rs.getInt("node_id"), 1.2 + rs.getDouble("score"), "term_weight")
                }
            }

            if (hits.size < limit) {
                val likeTerms = allTerms.take(16)
                val likeWhere = likeTerms.joinToString(" OR ") {
                    "(LOWER(memory_nodes.searchable_text) LIKE ? OR LOWER(memory_nodes.content) LIKE ? OR LOWER(memory_nodes.uri) LIKE ?)"
                }
                conn.prepareStatement("""
                    SELECT id
                    FROM memory_nodes
                    WHERE memory_nodes.status IN ($statusPlaceholders)
                      AND ($likeWhere)
                    ORDER BY updated_at DESC, uri ASC
                    LIMIT ?
                """.trimIndent()).use { pstmt ->
                    var position = 1
                    allowedStatuses.forEach { status -> pstmt.setString(position++, status) }
                    likeTerms.forEach { term ->
                        val like = "%$term%"
                        pstmt.setString(position++, like)
                        pstmt.setString(position++, like)
                        pstmt.setString(position++, like)
                    }
                    pstmt.setInt(position, limit * 2)
                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        addHit(rs.getInt("id"), 0.8, "like")
                    }
                }
            }
        }

        val nodesById = getMemoryNodesByIds(hits.keys).associateBy { it.id }
        return hits.mapNotNull { (id, hit) ->
            val node = nodesById[id] ?: return@mapNotNull null
            val searchText = "${node.searchableText} ${node.content} ${node.uri}".lowercase()
            val matched = allTerms.filter { searchText.contains(it) }.take(24)
            MemoryNodeSearchHit(
                node = node,
                textScore = hit.score,
                matchReason = hit.reasons.joinToString("+"),
                matchedTerms = matched
            )
        }
            .sortedWith(
                compareByDescending<MemoryNodeSearchHit> { it.textScore }
                    .thenByDescending { it.node.priority }
                    .thenByDescending { it.node.confidence }
                    .thenBy { it.node.uri }
                    .thenBy { it.node.id }
            )
            .take(limit)
    }

    fun rebuildMemoryTermEdges(maxTermsPerSource: Int = 12) {
        val now = Instant.now().epochSecond
        data class SourceTerm(val sourceId: String, val term: String, val weight: Double)
        data class EdgeAccumulator(var coCount: Int = 0, var weight: Double = 0.0)
        val sourceTerms = mutableListOf<SourceTerm>()

        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT 'n:' || node_id AS source_id, term, SUM(weight) AS weight
                FROM memory_search_terms
                GROUP BY node_id, term
                UNION ALL
                SELECT 'o:' || observation_id AS source_id, term, SUM(weight) AS weight
                FROM memory_observation_terms
                GROUP BY observation_id, term
            """.trimIndent()).use { pstmt ->
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    val term = rs.getString("term")?.trim()?.lowercase().orEmpty()
                    if (term.isNotBlank()) {
                        sourceTerms += SourceTerm(rs.getString("source_id"), term, rs.getDouble("weight"))
                    }
                }
            }

            val edges = linkedMapOf<Pair<String, String>, EdgeAccumulator>()
            sourceTerms.groupBy { it.sourceId }.values.forEach { terms ->
                val topTerms = terms
                    .groupBy { it.term }
                    .map { (term, values) -> term to values.sumOf { it.weight } }
                    .filter { it.first.isNotBlank() }
                    .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                    .take(maxTermsPerSource.coerceIn(2, 32))
                for (leftIndex in topTerms.indices) {
                    val left = topTerms[leftIndex]
                    for (right in topTerms.drop(leftIndex + 1)) {
                        if (left.first == right.first) continue
                        val a = minOf(left.first, right.first)
                        val b = maxOf(left.first, right.first)
                        val edge = edges.getOrPut(a to b) { EdgeAccumulator() }
                        edge.coCount += 1
                        edge.weight += (left.second + right.second) / 2.0
                    }
                }
            }

            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM memory_term_edges") }
                conn.prepareStatement("""
                    INSERT INTO memory_term_edges (term_a, term_b, co_count, weight, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()).use { insert ->
                    edges.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).forEach { (key, edge) ->
                        insert.setString(1, key.first)
                        insert.setString(2, key.second)
                        insert.setInt(3, edge.coCount)
                        insert.setDouble(4, edge.weight)
                        insert.setLong(5, now)
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun getRelatedMemoryTerms(terms: List<String>, limit: Int): List<MemoryTermEdgeRecord> {
        val normalizedTerms = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedTerms.isEmpty() || limit <= 0) return emptyList()
        val placeholders = normalizedTerms.joinToString(",") { "?" }
        val sourceSet = normalizedTerms.toSet()
        val list = mutableListOf<MemoryTermEdgeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT term_a, term_b, co_count, weight, updated_at
                FROM memory_term_edges
                WHERE term_a IN ($placeholders) OR term_b IN ($placeholders)
                ORDER BY weight DESC, co_count DESC, term_a ASC, term_b ASC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                var position = 1
                normalizedTerms.forEach { term -> pstmt.setString(position++, term) }
                normalizedTerms.forEach { term -> pstmt.setString(position++, term) }
                pstmt.setInt(position, limit * 4)
                val rs = pstmt.executeQuery()
                while (rs.next() && list.size < limit) {
                    val a = rs.getString("term_a")
                    val b = rs.getString("term_b")
                    if (a in sourceSet && b in sourceSet) continue
                    list += MemoryTermEdgeRecord(
                        termA = a,
                        termB = b,
                        coCount = rs.getInt("co_count"),
                        weight = rs.getDouble("weight"),
                        updatedAt = rs.getLong("updated_at")
                    )
                }
            }
        }
        return list
    }

    fun getTopMemoryTermEdges(limit: Int): List<MemoryTermEdgeRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<MemoryTermEdgeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT term_a, term_b, co_count, weight, updated_at
                FROM memory_term_edges
                ORDER BY weight DESC, co_count DESC, term_a ASC, term_b ASC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list += MemoryTermEdgeRecord(
                        termA = rs.getString("term_a"),
                        termB = rs.getString("term_b"),
                        coCount = rs.getInt("co_count"),
                        weight = rs.getDouble("weight"),
                        updatedAt = rs.getLong("updated_at")
                    )
                }
            }
        }
        return list
    }

    fun memoryTermGraphStats(): MemoryTermGraphStats {
        getConnection().use { conn ->
            val edgeCount = conn.prepareStatement("SELECT COUNT(*) FROM memory_term_edges").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) rs.getInt(1) else 0
            }
            val termCount = conn.prepareStatement("""
                SELECT COUNT(*) FROM (
                    SELECT term_a AS term FROM memory_term_edges
                    UNION
                    SELECT term_b AS term FROM memory_term_edges
                )
            """.trimIndent()).use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) rs.getInt(1) else 0
            }
            val updatedAt = conn.prepareStatement("SELECT COALESCE(MAX(updated_at), 0) FROM memory_term_edges").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) rs.getLong(1) else 0L
            }
            return MemoryTermGraphStats(termCount, edgeCount, updatedAt, dirty = false, error = null)
        }
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

    fun getGraphMemoryNodesByCreatedRange(
        minCreatedAt: Long?,
        maxCreatedAt: Long?,
        limit: Int,
        statuses: Set<String> = setOf("active")
    ): List<MemoryNodeRecord> {
        if (limit <= 0) return emptyList()
        val allowedStatuses = statuses.ifEmpty { setOf("active") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (allowedStatuses.isEmpty()) return emptyList()
        val list = mutableListOf<MemoryNodeRecord>()
        val conditions = mutableListOf<String>()
        val statusPlaceholders = allowedStatuses.joinToString(",") { "?" }
        conditions += "status IN ($statusPlaceholders)"
        if (minCreatedAt != null) conditions += "created_at >= ?"
        if (maxCreatedAt != null) conditions += "created_at <= ?"
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_nodes
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY created_at DESC, priority DESC, confidence DESC, uri ASC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                var position = 1
                allowedStatuses.forEach { status -> pstmt.setString(position++, status) }
                if (minCreatedAt != null) pstmt.setLong(position++, minCreatedAt)
                if (maxCreatedAt != null) pstmt.setLong(position++, maxCreatedAt)
                pstmt.setInt(position, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readMemoryNode(rs))
            }
        }
        return list
    }

    fun getOldWeakGraphMemoryNodes(limit: Int, olderThanSeconds: Long, maxStrength: Double): List<MemoryNodeRecord> {
        if (limit <= 0) return emptyList()
        val cutoff = Instant.now().epochSecond - olderThanSeconds.coerceAtLeast(0L)
        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_nodes
                WHERE status = 'active'
                  AND updated_at <= ?
                  AND strength <= ?
                ORDER BY strength ASC, updated_at ASC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, cutoff)
                pstmt.setDouble(2, maxStrength)
                pstmt.setInt(3, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryNode(rs))
                }
            }
        }
        return list
    }

    fun getEmotionallySalientGraphMemoryNodes(limit: Int, minArousal: Double = 0.55): List<MemoryNodeRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_nodes
                WHERE status = 'active'
                  AND (emotion_arousal >= ? OR emotion_valence <= 0.30 OR emotion_valence >= 0.70)
                ORDER BY emotion_arousal DESC, priority DESC, updated_at DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, minArousal)
                pstmt.setInt(2, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    list.add(readMemoryNode(rs))
                }
            }
        }
        return list
    }

    fun getActiveWorkingMemoryNodes(projectUri: String?, limit: Int): List<MemoryNodeRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_nodes
                WHERE status = 'active'
                  AND kind = 'working_memory'
                  AND ((? IS NULL AND project_uri IS NULL) OR project_uri = ?)
                ORDER BY updated_at DESC, strength DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setNullableString(1, projectUri)
                pstmt.setNullableString(2, projectUri)
                pstmt.setInt(3, limit)
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

    /**
     * Persist the lazily-decayed strength back onto the node, resetting last_accessed_at to
     * `now` so the next lazyStrength computation does not double-count the elapsed time.
     * Called by MemoryService.applyLazyStrengthDecay before the decay job judges which
     * nodes to archive.
     */
    fun updateMemoryNodeStrength(nodeId: Int, strength: Double, lastAccessedAt: Long) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_nodes
                SET strength = ?, last_accessed_at = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, strength)
                pstmt.setLong(2, lastAccessedAt)
                pstmt.setInt(3, nodeId)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * All active graph nodes (no self-uri filter — self nodes decay too; the exemption
     * applies only at the archive step in decayGraphMemoryNodes). Used by
     * MemoryService.applyLazyStrengthDecay to persist decayed strength per-node (tau is
     * salience-dependent and computed in MemoryService, so it cannot be expressed in SQL).
     */
    fun getActiveGraphMemoryNodesForDecay(): List<MemoryNodeRecord> {
        val list = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT * FROM memory_nodes
                WHERE status = 'active'
            """.trimIndent()).use { pstmt ->
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readMemoryNode(rs))
            }
        }
        return list
    }

    fun decayGraphMemoryNodes(threshold: Double) {
        // Archive (route through recycle bin) instead of hard-deleting. Archiving keeps the
        // row so memory_nodes_fts stays in sync and edges don't go dangling; recall already
        // filters status='active' so archived nodes won't surface. The recycle bin row lets
        // compressExpiredRecycleBin tombstone and later purge them on its own schedule.
        val toArchive = mutableListOf<MemoryNodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT * FROM memory_nodes
                WHERE status = 'active'
                  AND strength < ?
                  AND uri NOT LIKE 'self://%'
                  AND COALESCE(person_uri, '') != 'person://self/kiyomizu'
            """.trimIndent()).use { pstmt ->
                pstmt.setDouble(1, threshold)
                val rs = pstmt.executeQuery()
                while (rs.next()) toArchive.add(readMemoryNode(rs))
            }
        }
        for (node in toArchive) {
            archiveMemoryNodeToRecycle(
                node,
                reason = "decay_below_threshold",
                retentionDays = Config.memoryRecycleRetentionDays
            )
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

    fun listSelfMemoryNodes(status: String?, limit: Int): List<MemoryNodeRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<MemoryNodeRecord>()
        val sql = buildString {
            append("""
                SELECT *
                FROM memory_nodes
                WHERE (uri LIKE 'self://%' OR person_uri = 'person://self/kiyomizu')
            """.trimIndent())
            if (!status.isNullOrBlank()) {
                append(" AND status = ?")
            }
            append(" ORDER BY priority DESC, confidence DESC, uri ASC, id ASC LIMIT ?")
        }
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var position = 1
                if (!status.isNullOrBlank()) pstmt.setString(position++, status)
                pstmt.setInt(position, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readMemoryNode(rs))
            }
        }
        return list
    }

    fun listSelfMemoryObservations(status: String?, limit: Int): List<MemoryObservationRecord> {
        if (limit <= 0) return emptyList()
        val list = mutableListOf<MemoryObservationRecord>()
        val sql = buildString {
            append("""
                SELECT *
                FROM memory_observations
                WHERE (candidate_uri LIKE 'self://%' OR person_uri = 'person://self/kiyomizu')
            """.trimIndent())
            if (!status.isNullOrBlank()) {
                append(" AND status = ?")
            }
            append(" ORDER BY last_seen_at DESC, priority DESC, confidence DESC, id DESC LIMIT ?")
        }
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { pstmt ->
                var position = 1
                if (!status.isNullOrBlank()) pstmt.setString(position++, status)
                pstmt.setInt(position, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readMemoryObservation(rs))
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

    // --- Materialized memory index and model recall traces ---
    private fun readMemoryIndexSegment(rs: java.sql.ResultSet): MemoryIndexSegmentRecord {
        return MemoryIndexSegmentRecord(
            segmentKey = rs.getString("segment_key"),
            content = rs.getString("content"),
            version = rs.getInt("version"),
            dirty = rs.getInt("dirty") != 0,
            error = rs.getString("error"),
            charCount = rs.getInt("char_count"),
            updatedAt = rs.getLong("updated_at")
        )
    }

    fun upsertMemoryIndexSegment(segmentKey: String, content: String, error: String? = null, dirty: Boolean = false) {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            val existing = conn.prepareStatement("""
                SELECT *
                FROM memory_index_segments
                WHERE segment_key = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, segmentKey)
                val rs = pstmt.executeQuery()
                if (rs.next()) readMemoryIndexSegment(rs) else null
            }
            val version = if (existing == null) {
                1
            } else if (existing.content != content || existing.dirty != dirty || existing.error != error) {
                existing.version + 1
            } else {
                existing.version
            }
            conn.prepareStatement("""
                INSERT INTO memory_index_segments (segment_key, content, version, dirty, error, char_count, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(segment_key) DO UPDATE SET
                    content = excluded.content,
                    version = excluded.version,
                    dirty = excluded.dirty,
                    error = excluded.error,
                    char_count = excluded.char_count,
                    updated_at = excluded.updated_at
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, segmentKey)
                pstmt.setString(2, content)
                pstmt.setInt(3, version)
                pstmt.setInt(4, if (dirty) 1 else 0)
                pstmt.setNullableString(5, error)
                pstmt.setInt(6, content.length)
                pstmt.setLong(7, now)
                pstmt.executeUpdate()
            }
        }
    }

    fun markMemoryIndexSegmentsDirty(error: String) {
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_index_segments
                SET dirty = 1, error = ?, updated_at = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, error)
                pstmt.setLong(2, Instant.now().epochSecond)
                pstmt.executeUpdate()
            }
        }
    }

    fun listMemoryIndexSegments(): List<MemoryIndexSegmentRecord> {
        val list = mutableListOf<MemoryIndexSegmentRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_index_segments
                ORDER BY segment_key ASC
            """.trimIndent()).use { pstmt ->
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readMemoryIndexSegment(rs))
            }
        }
        return list
    }

    private fun readModelRecallTrace(rs: java.sql.ResultSet): ModelRecallTraceRecord {
        return ModelRecallTraceRecord(
            id = rs.getInt("id"),
            createdAt = rs.getLong("created_at"),
            query = rs.getString("query"),
            indexVersion = rs.getString("index_version"),
            planJson = rs.getString("plan_json"),
            candidateCount = rs.getInt("candidate_count"),
            injectedCount = rs.getInt("injected_count"),
            filteredSummary = rs.getString("filtered_summary"),
            fallbackReason = rs.getString("fallback_reason"),
            error = rs.getString("error"),
            durationMs = rs.getInt("duration_ms"),
            debugJson = rs.getString("debug_json")
        )
    }

    fun insertModelRecallTrace(
        query: String,
        indexVersion: String,
        planJson: String?,
        candidateCount: Int,
        injectedCount: Int,
        filteredSummary: String?,
        fallbackReason: String?,
        error: String?,
        durationMs: Int,
        debugJson: String? = null
    ): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memory_model_recall_traces (
                    created_at, query, index_version, plan_json, candidate_count, injected_count,
                    filtered_summary, fallback_reason, error, duration_ms, debug_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, now)
                pstmt.setString(2, query)
                pstmt.setString(3, indexVersion)
                pstmt.setNullableString(4, planJson)
                pstmt.setInt(5, candidateCount)
                pstmt.setInt(6, injectedCount)
                pstmt.setNullableString(7, filteredSummary)
                pstmt.setNullableString(8, fallbackReason)
                pstmt.setNullableString(9, error)
                pstmt.setInt(10, durationMs)
                pstmt.setNullableString(11, debugJson)
                pstmt.executeUpdate()
            }
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM memory_model_recall_traces WHERE id NOT IN " +
                        "(SELECT id FROM memory_model_recall_traces ORDER BY id DESC LIMIT ${Config.memoryModelRecallTraceRetention.coerceAtLeast(1)})"
                )
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return -1
    }

    /**
     * Overwrite the debug_json of an existing model-recall trace (e.g. to record what the
     * local fallback ended up injecting after a model-recall failure). No-op for traceId <= 0
     * (insertModelRecallTrace returns -1 on failure). Does not run the retention DELETE — the
     * row already exists, so row count is unchanged.
     */
    fun updateModelRecallTraceDebugJson(traceId: Int, debugJson: String) {
        if (traceId <= 0) return
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE memory_model_recall_traces
                SET debug_json = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                pstmt.setNullableString(1, debugJson)
                pstmt.setInt(2, traceId)
                pstmt.executeUpdate()
            }
        }
    }

    fun getRecentModelRecallTraces(limit: Int): List<ModelRecallTraceRecord> {
        val list = mutableListOf<ModelRecallTraceRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM memory_model_recall_traces
                ORDER BY id DESC
                LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit.coerceAtLeast(1))
                val rs = pstmt.executeQuery()
                while (rs.next()) list.add(readModelRecallTrace(rs))
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
        val explicitCacheBlocks: Int?,
        val cacheMode: String?,
        val cacheStrategy: String?,
        val cacheBreakpoints: Int?,
        val cacheBreakpointIndexes: List<Int>,
        val patchEligible: Boolean?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cacheReadInputTokens: Int?,
        val cacheCreationInputTokens: Int?,
        val cachedPromptTokens: Int?,
        val usageJson: String?
    )

    data class RequestUsageDraft(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val cacheReadInputTokens: Int? = null,
        val cacheCreationInputTokens: Int? = null,
        val cachedPromptTokens: Int? = null,
        val usageJson: String? = null
    )

    fun insertRequestLog(
        at: String,
        method: String,
        pathname: String,
        patched: Boolean,
        removedThinkingBlocks: Int,
        model: String?,
        messageCount: Int?,
        explicitCacheBlocks: Int?,
        cacheMode: String? = null,
        cacheStrategy: String? = null,
        cacheBreakpoints: Int? = null,
        cacheBreakpointIndexes: List<Int> = emptyList(),
        patchEligible: Boolean? = null
    ): Int {
        val now = Instant.now().epochSecond
        getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO request_logs (
                    at, method, pathname, patched, removed_thinking_blocks, model, message_count,
                    explicit_cache_blocks, cache_mode, cache_strategy, cache_breakpoints,
                    cache_breakpoint_indexes, patch_eligible, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { pstmt ->
                pstmt.setString(1, at)
                pstmt.setString(2, method)
                pstmt.setString(3, pathname)
                pstmt.setInt(4, if (patched) 1 else 0)
                pstmt.setInt(5, removedThinkingBlocks)
                if (model != null) pstmt.setString(6, model) else pstmt.setNull(6, java.sql.Types.VARCHAR)
                if (messageCount != null) pstmt.setInt(7, messageCount) else pstmt.setNull(7, java.sql.Types.INTEGER)
                if (explicitCacheBlocks != null) pstmt.setInt(8, explicitCacheBlocks) else pstmt.setNull(8, java.sql.Types.INTEGER)
                pstmt.setNullableString(9, cacheMode)
                pstmt.setNullableString(10, cacheStrategy)
                if (cacheBreakpoints != null) pstmt.setInt(11, cacheBreakpoints) else pstmt.setNull(11, java.sql.Types.INTEGER)
                pstmt.setString(12, buildJsonArray { cacheBreakpointIndexes.forEach { add(JsonPrimitive(it)) } }.toString())
                if (patchEligible != null) pstmt.setInt(13, if (patchEligible) 1 else 0) else pstmt.setNull(13, java.sql.Types.INTEGER)
                pstmt.setLong(14, now)
                pstmt.executeUpdate()
            }
            // Keep only the newest 1000 entries
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM request_logs WHERE id NOT IN (SELECT id FROM request_logs ORDER BY id DESC LIMIT 1000)")
            }
            conn.prepareStatement("SELECT last_insert_rowid()").use { pstmt ->
                val rs = pstmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return -1
    }

    fun updateRequestLogUsage(logId: Int, usage: RequestUsageDraft) {
        if (logId <= 0) return
        getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE request_logs
                SET input_tokens = ?,
                    output_tokens = ?,
                    cache_read_input_tokens = ?,
                    cache_creation_input_tokens = ?,
                    cached_prompt_tokens = ?,
                    usage_json = ?
                WHERE id = ?
            """.trimIndent()).use { pstmt ->
                if (usage.inputTokens != null) pstmt.setInt(1, usage.inputTokens) else pstmt.setNull(1, java.sql.Types.INTEGER)
                if (usage.outputTokens != null) pstmt.setInt(2, usage.outputTokens) else pstmt.setNull(2, java.sql.Types.INTEGER)
                if (usage.cacheReadInputTokens != null) pstmt.setInt(3, usage.cacheReadInputTokens) else pstmt.setNull(3, java.sql.Types.INTEGER)
                if (usage.cacheCreationInputTokens != null) pstmt.setInt(4, usage.cacheCreationInputTokens) else pstmt.setNull(4, java.sql.Types.INTEGER)
                if (usage.cachedPromptTokens != null) pstmt.setInt(5, usage.cachedPromptTokens) else pstmt.setNull(5, java.sql.Types.INTEGER)
                pstmt.setNullableString(6, usage.usageJson)
                pstmt.setInt(7, logId)
                pstmt.executeUpdate()
            }
        }
    }

    fun getRecentRequestLogs(limit: Int): List<RequestLogRecord> {
        val list = mutableListOf<RequestLogRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT *
                FROM request_logs ORDER BY id DESC LIMIT ?
            """.trimIndent()).use { pstmt ->
                pstmt.setInt(1, limit)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    fun nullableInt(column: String): Int? {
                        val value = rs.getInt(column)
                        return if (rs.wasNull()) null else value
                    }
                    val patchEligibleValue = nullableInt("patch_eligible")
                    list.add(RequestLogRecord(
                        id = rs.getInt("id"),
                        at = rs.getString("at"),
                        method = rs.getString("method"),
                        pathname = rs.getString("pathname"),
                        patched = rs.getInt("patched") != 0,
                        removedThinkingBlocks = rs.getInt("removed_thinking_blocks"),
                        model = rs.getString("model"),
                        messageCount = nullableInt("message_count"),
                        explicitCacheBlocks = nullableInt("explicit_cache_blocks"),
                        cacheMode = rs.getString("cache_mode"),
                        cacheStrategy = rs.getString("cache_strategy"),
                        cacheBreakpoints = nullableInt("cache_breakpoints"),
                        cacheBreakpointIndexes = parseRelatedIds(rs.getString("cache_breakpoint_indexes")),
                        patchEligible = patchEligibleValue?.let { it != 0 },
                        inputTokens = nullableInt("input_tokens"),
                        outputTokens = nullableInt("output_tokens"),
                        cacheReadInputTokens = nullableInt("cache_read_input_tokens"),
                        cacheCreationInputTokens = nullableInt("cache_creation_input_tokens"),
                        cachedPromptTokens = nullableInt("cached_prompt_tokens"),
                        usageJson = rs.getString("usage_json")
                    ))
                }
            }
        }
        return list
    }
}
