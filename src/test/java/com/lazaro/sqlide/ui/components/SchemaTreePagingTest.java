package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaTreePagingTest {

    @Test
    void smallFoldersAreNotCapped() {
        List<SchemaNode> source = tables(20);
        SchemaTreePaging.Page page = SchemaTreePaging.slice(source, "", SchemaTreePaging.PAGE_SIZE);
        assertEquals(20, page.visible().size());
        assertFalse(page.hasMore());
        assertEquals(20, page.matched());
    }

    @Test
    void largeFoldersYieldOnePageAndARemainder() {
        List<SchemaNode> source = tables(1_200);
        SchemaTreePaging.Page page = SchemaTreePaging.slice(source, "", SchemaTreePaging.PAGE_SIZE);
        assertEquals(SchemaTreePaging.PAGE_SIZE, page.visible().size());
        assertEquals("t_1", page.visible().getFirst().name());
        assertEquals("t_150", page.visible().getLast().name());
        assertEquals(1_050, page.remaining());
        assertEquals(1_200, page.matched());
        assertTrue(page.hasMore());
    }

    @Test
    void filterAppliesBeforePaging() {
        List<SchemaNode> source = tables(400);
        source.add(SchemaNode.of("users", NodeType.TABLE, Map.of()));
        SchemaTreePaging.Page page = SchemaTreePaging.slice(source, "user", SchemaTreePaging.PAGE_SIZE);
        assertEquals(List.of("users"), page.visible().stream().map(SchemaNode::name).toList());
        assertFalse(page.hasMore());
    }

    @Test
    void indexOfFindsATablePastTheFirstPage() {
        List<SchemaNode> source = tables(400);
        assertEquals(299, SchemaTreePaging.indexOf(source, NodeType.TABLE, "t_300"));
        assertEquals(-1, SchemaTreePaging.indexOf(source, NodeType.TABLE, "missing"));
    }

    @Test
    void showMoreLabelMentionsHowManyAreLeft() {
        assertEquals("Show more (50 of 200 remaining)", SchemaTreePaging.showMoreLabel(50, 200));
        assertEquals("Show more (1 of 151 remaining)", SchemaTreePaging.showMoreLabel(1, 151));
    }

    @Test
    void groupingFoldersAreNotAPagedNameList() {
        List<SchemaNode> folders = List.of(
                SchemaNode.folder("tables", SchemaNode.FOLDER_TABLES, 12, Map.of()),
                SchemaNode.folder("views", SchemaNode.FOLDER_VIEWS, 2, Map.of()));
        assertTrue(SchemaTreePaging.groupsByFolder(folders));
        assertFalse(SchemaTreePaging.groupsByFolder(tables(3)));
        assertEquals("TABLE\0orders", SchemaTreePaging.identityKey(SchemaNode.of("orders", NodeType.TABLE)));
    }

    private static List<SchemaNode> tables(int count) {
        List<SchemaNode> tables = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            tables.add(SchemaNode.of("t_" + i, NodeType.TABLE, Map.of()));
        }
        return tables;
    }
}
