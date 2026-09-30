package com.notebookplush.storage

import com.notebookplush.model.Document
import com.notebookplush.model.Workspace
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCodecTest {
    @Test fun preservesEveryFileAndActiveSelectionWithoutChangingJson() {
        val text = "{\r\n  \"name\": \"🧸\", \"values\": [true, null, 1.2e-3]\r\n}\r\n"
        val first = Document(19, "first.json", text, "content://example/19", text, 2, 7)
        val second = Document(24, "second.txt", "tabs\tand lines\n", savedText = "before")
        val original = Workspace(listOf(first, second), second.id)
        assertEquals(original, WorkspaceCodec.decode(WorkspaceCodec.encode(original)))
    }
    @Test fun selectingAndClosingFilesKeepsTheOtherDrafts() {
        val first = Document(1, text = "one")
        val second = Document(2, text = "two")
        val third = Document(3, text = "three")
        val workspace = Workspace(listOf(first, second, third), 2)
        assertEquals(listOf(first, third), workspace.close(2).documents)
        assertEquals(3L, workspace.close(2).activeId)
        assertEquals(2L, workspace.close(1).activeId)
        assertEquals("one", workspace.select(1).active.text)
        assertEquals(1, Workspace(listOf(first)).close(1).documents.size)
    }
    @Test fun rejectsDuplicateIdsAndMissingActiveFile() {
        val source = WorkspaceCodec.encode(Workspace(listOf(Document(1), Document(2))))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCodec.decode(source.replace("\"id\":2", "\"id\":1")) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCodec.decode(source.replace("\"activeId\":1", "\"activeId\":7")) }
    }
    @Test fun closingSeveralTabsKeepsTheRequestedInactiveFileAndUsesANeighborOtherwise() {
        val files = (1L..5L).map { Document(it, text = "draft $it") }
        val workspace = Workspace(files, 3)
        val onlySecond = workspace.close(setOf(1, 3, 4, 5), keepId = 2)
        assertEquals(listOf(files[1]), onlySecond.documents)
        assertEquals(2L, onlySecond.activeId)
        val neighbors = workspace.close(setOf(2, 3, 4))
        assertEquals(listOf(files[0], files[4]), neighbors.documents)
        assertEquals(5L, neighbors.activeId)
        assertEquals(Workspace(listOf(files[0]), 1), workspace.close(setOf(2, 3, 4, 5)))
    }
    @Test fun closingAllFilesLeavesANewEmptyDraftAndRoundTripsIt() {
        val workspace = Workspace(listOf(Document(4, text = "keep bytes"), Document(9, text = "other")), 4)
        val closed = workspace.close(setOf(4, 9))
        assertEquals(listOf(Document(10)), closed.documents)
        assertEquals(10L, closed.activeId)
        assertEquals(closed, WorkspaceCodec.decode(WorkspaceCodec.encode(closed)))
    }
}
