package hifumi.kiyomizu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the memory-graph CRUD / import-export surface added by the
 * "记忆导入导出 + 编辑 + 删除" feature:
 *   - generic node edit preserves FSRS runtime fields (stability/strength/access_count)
 *   - soft delete (archive) -> restore -> active; purge removes node + incident edges (FK CASCADE)
 *   - hard delete of a single edge
 *   - neighbors returns correct endpoints
 *   - export -> import replace round-trip preserves node/edge/stability; dangling edges skipped
 */
class MemoryManagementTest {
    private val tempDir = Files.createTempDirectory("kiyomizu-memmgmt-test")
    private val testDbPath = tempDir.resolve("kiyomizu_memmgmt.db").toString()

    init {
        System.setProperty("kiyomizu.db.file", testDbPath)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("kiyomizu.db.file")
        tempDir.toFile().deleteRecursively()
    }

    private fun resetConfig() {
        Config.memoryRecycleRetentionDays = 30
        Config.memoryEnabled = false
        Config.memoryInitialStrength = 0.8
        Config.memoryMaxStrength = 1.0
        Config.memoryRecoveryAmount = 0.3
        Config.memoryDecayRate = 0.1
        Config.memoryThreshold = 0.1
        Config.memoryDecayTauHours = 360.0
        Config.memoryStabilityMin = 1.0
        Config.memoryStabilityMax = 8.0
    }

    private fun resetDbFiles() {
        listOf(
            java.io.File(testDbPath),
            java.io.File("${testDbPath}-wal"),
            java.io.File("${testDbPath}-shm")
        ).forEach { if (it.exists()) it.delete() }
    }

    private fun seedNode(
        uri: String,
        kind: String = "preference",
        content: String = "seed content",
        keywords: List<String> = listOf("seed"),
        strength: Double = 0.8,
        disclosure: String = "private",
        status: String = "active"
    ): Int {
        val draft = DatabaseService.MemoryNodeDraft(
            uri = uri,
            kind = kind,
            content = content,
            normalizedText = content.trim().lowercase(),
            searchableText = listOf(content, keywords.joinToString(" "), uri).joinToString(" "),
            keywords = keywords,
            disclosure = disclosure,
            priority = 0.5,
            confidence = 0.6,
            strength = strength,
            status = status
        )
        val id = DatabaseService.insertMemoryNode(draft)
        DatabaseService.replaceMemorySearchTerms(
            id,
            keywords.map { DatabaseService.MemorySearchTermDraft(it.lowercase(), "keyword") }
        )
        return id
    }

    /** Directly set a node's stability/access_count to non-default values for read-only checks. */
    private fun stampRuntimeState(nodeId: Int, stability: Double, accessCount: Int, strength: Double) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$testDbPath").use { conn ->
            conn.prepareStatement(
                "UPDATE memory_nodes SET stability = ?, access_count = ?, strength = ? WHERE id = ?"
            ).use { ps ->
                ps.setDouble(1, stability)
                ps.setInt(2, accessCount)
                ps.setDouble(3, strength)
                ps.setInt(4, nodeId)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun editNodeChangesContentButPreservesRuntimeFields() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val id = seedNode(uri = "identity://user/test", kind = "identity", content = "original content")
        stampRuntimeState(id, stability = 4.25, accessCount = 7, strength = 0.55)

        val before = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(4.25, before.stability)
        assertEquals(7, before.accessCount)
        assertEquals(0.55, before.strength)

        val patch = buildJsonObject {
            put("content", "edited content")
            put("priority", 0.42)
            put("keywords", buildJsonArray { add(JsonPrimitive("edited")) })
        }
        val edited = MemoryService.editMemoryNode(id, patch)
        assertNotNull(edited)
        assertEquals("edited content", edited.content)
        assertEquals(0.42, edited.priority)
        // Runtime-read-only fields must be untouched by an edit.
        assertEquals(4.25, edited.stability, "stability must be preserved across edit")
        assertEquals(7, edited.accessCount, "access_count must be preserved across edit")
        assertEquals(0.55, edited.strength, "strength must be preserved across edit")

        // Reject attempt to edit a runtime field directly.
        val rejected = MemoryService.editMemoryNode(id, buildJsonObject { put("stability", 9.9) })
        assertNull(rejected, "an edit touching stability must be rejected")
        // …and the node must be unchanged after the rejected attempt.
        val still = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(4.25, still.stability)
    }

    @Test
    fun softDeleteArchivesRestoresAndPurgeRemovesNodeAndIncidentEdges() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val a = seedNode(uri = "person://a", kind = "identity", content = "A")
        val b = seedNode(uri = "preference://b", kind = "preference", content = "B")
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, b, "about_person", 1.0))
        assertEquals(2, DatabaseService.getGraphNodeCount())
        assertEquals(1, DatabaseService.getGraphEdgeCount())

        // soft delete: a -> archived + recycle-bin row, edge untouched, restorable.
        assertTrue(MemoryService.softDeleteMemoryNode(a, "manual delete"))
        val archived = DatabaseService.getMemoryNodeById(a)!!
        assertEquals("archived", archived.status)
        assertTrue(DatabaseService.listRecycleBin(20).any { it.uri == "person://a" })
        // edge still exists (soft delete does not cascade).
        assertEquals(1, DatabaseService.getGraphEdgeCount())

        // restore: back to active.
        assertTrue(MemoryService.restoreMemoryNode(a))
        assertEquals("active", DatabaseService.getMemoryNodeById(a)!!.status)

        // purge: hard delete node + incident edges via FK CASCADE.
        assertTrue(MemoryService.purgeMemoryNode(a))
        assertNull(DatabaseService.getMemoryNodeById(a))
        assertEquals(0, DatabaseService.getGraphEdgeCount(), "incident edge must cascade-delete with the node")
        assertEquals(1, DatabaseService.getGraphNodeCount())
    }

    @Test
    fun deleteEdgeRemovesOnlyThatEdge() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val a = seedNode(uri = "person://a", content = "A")
        val b = seedNode(uri = "preference://b", content = "B")
        val c = seedNode(uri = "preference://c", content = "C")
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, b, "rel_ab", 1.0))
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, c, "rel_ac", 0.5))
        assertEquals(2, DatabaseService.getGraphEdgeCount())

        val abEdge = DatabaseService.listAllMemoryEdges(100)
            .first { it.relation == "rel_ab" }
        assertTrue(MemoryService.deleteMemoryEdge(abEdge.id))
        val remaining = DatabaseService.listAllMemoryEdges(100)
        assertEquals(1, remaining.size)
        assertEquals("rel_ac", remaining.first().relation)
    }

    @Test
    fun neighborsReturnsCorrectEndpoints() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val a = seedNode(uri = "person://a", content = "A")
        val b = seedNode(uri = "person://b", content = "B")
        val c = seedNode(uri = "person://c", content = "C")
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, b, "knows", 1.0))
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(c, a, "known_by", 0.8))

        val neighbors = MemoryService.getMemoryNodeNeighbors(a)!!
        // b (a->b) and c (c->a) should both be neighbors; a itself excluded.
        val uris = neighbors.neighbors.map { it.uri }.sorted()
        assertEquals(listOf("person://b", "person://c"), uris)
        assertEquals(2, neighbors.edges.size)
    }

    @Test
    fun exportImportRoundTripPreservesNodesEdgesStabilityAndSkipsDanglingEdges() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val a = seedNode(uri = "person://a", kind = "identity", content = "anchor")
        val b = seedNode(uri = "preference://b", kind = "preference", content = "pref")
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, b, "about_person", 1.0))
        stampRuntimeState(a, stability = 5.5, accessCount = 12, strength = 0.7)
        stampRuntimeState(b, stability = 2.3, accessCount = 3, strength = 0.4)

        val bundle = MemoryService.exportMemoryBundle(null, null, null, null, null)
        val nodes = bundle["nodes"]!!.jsonArray
        assertEquals(2, nodes.size)
        val edgeArr = bundle["edges"]!!.jsonArray
        assertEquals(1, edgeArr.size)
        assertEquals("about_person", edgeArr.first().jsonObject["relation"]!!.jsonPrimitive.content)

        // Mutate the bundle to add a dangling edge (endpoint URI not present in nodes).
        val bundleWithDangling = JsonObject(bundle.toMap() + (
            "edges" to buildJsonArray {
                edgeArr.forEach { add(it) }
                add(buildJsonObject {
                    put("from_uri", "person://a")
                    put("to_uri", "person://ghost") // not in bundle -> must be skipped
                    put("relation", "dangling")
                    put("weight", 1.0)
                })
            }
        ))

        // Preview surfaces the skip count.
        val preview = MemoryService.previewImportBundle(bundleWithDangling)
        assertEquals(1, preview["skipped_edges"]!!.jsonPrimitive.int)
        assertEquals(2, preview["import_nodes"]!!.jsonPrimitive.int)
        assertEquals(1, preview["import_edges"]!!.jsonPrimitive.int)

        // Import replaces the whole graph, remaps edges by URI, skips the dangling edge.
        val result = MemoryService.importMemoryBundle(bundleWithDangling)
        assertEquals(true, result["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false)
        assertEquals(2, result["inserted_nodes"]!!.jsonPrimitive.int)
        assertEquals(1, result["inserted_edges"]!!.jsonPrimitive.int)
        assertEquals(1, result["skipped_edges"]!!.jsonPrimitive.int)

        // The graph was replaced: ids may differ but URIs, edges, and stability are preserved.
        assertEquals(2, DatabaseService.getGraphNodeCount())
        assertEquals(1, DatabaseService.getGraphEdgeCount())

        val newA = DatabaseService.getMemoryNodeByUri("person://a")!!
        assertEquals(5.5, newA.stability, "stability must survive import round-trip")
        assertEquals(12, newA.accessCount, "access_count must survive import round-trip")
        assertEquals(0.7, newA.strength, "strength must survive import round-trip")
        val newB = DatabaseService.getMemoryNodeByUri("preference://b")!!
        assertEquals(2.3, newB.stability)

        // The re-imported edge connects the re-id'd endpoints by URI.
        val edges = DatabaseService.listAllMemoryEdges(100)
        assertEquals(1, edges.size)
        assertEquals("about_person", edges.first().relation)
        assertEquals(newA.id, edges.first().fromNodeId)
        assertEquals(newB.id, edges.first().toNodeId)
    }

    @Test
    fun exportFiltersByKindDropsNonMatchingEdges() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val a = seedNode(uri = "person://a", kind = "identity", content = "A")
        val b = seedNode(uri = "preference://b", kind = "preference", content = "B")
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, b, "about_person", 1.0))

        // Export only identity nodes: edge to a preference node has an endpoint outside the
        // filtered set and must be excluded from the bundle.
        val bundle = MemoryService.exportMemoryBundle(null, null, "identity", null, null)
        assertEquals(1, bundle["nodes"]!!.jsonArray.size)
        assertEquals(0, bundle["edges"]!!.jsonArray.size, "edges with an endpoint outside the filter are dropped")
    }
}