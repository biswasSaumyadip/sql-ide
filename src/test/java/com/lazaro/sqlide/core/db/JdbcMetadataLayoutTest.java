package com.lazaro.sqlide.core.db;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcMetadataLayoutTest {

    @Test
    void firstCatalogHitIsRememberedAndSchemaIsNotTriedAgain() throws Exception {
        JdbcMetadataLayout layout = new JdbcMetadataLayout();
        AtomicInteger catalogCalls = new AtomicInteger();
        AtomicInteger schemaCalls = new AtomicInteger();

        List<String> first = layout.read("app", (catalog, schema) -> {
            count(catalog, schema, catalogCalls, schemaCalls);
            return catalog != null ? List.of("users") : List.of();
        }, List::isEmpty);
        assertEquals(List.of("users"), first);
        assertEquals(JdbcMetadataLayout.Slot.CATALOG, layout.slot().orElseThrow());

        List<String> second = layout.read("app", (catalog, schema) -> {
            count(catalog, schema, catalogCalls, schemaCalls);
            return catalog != null ? List.of("users") : List.of("should-not-run");
        }, List::isEmpty);
        assertEquals(List.of("users"), second);
        assertEquals(2, catalogCalls.get());
        assertEquals(0, schemaCalls.get(), "cached catalog layout must not probe schema");
    }

    @Test
    void emptyCatalogThenSchemaHitRemembersSchema() throws Exception {
        JdbcMetadataLayout layout = new JdbcMetadataLayout();
        AtomicInteger catalogCalls = new AtomicInteger();
        AtomicInteger schemaCalls = new AtomicInteger();

        List<String> first = layout.read("public", (catalog, schema) -> {
            count(catalog, schema, catalogCalls, schemaCalls);
            return schema != null ? List.of("orders") : List.of();
        }, List::isEmpty);
        assertEquals(List.of("orders"), first);
        assertEquals(JdbcMetadataLayout.Slot.SCHEMA, layout.slot().orElseThrow());

        List<String> second = layout.read("public", (catalog, schema) -> {
            count(catalog, schema, catalogCalls, schemaCalls);
            return schema != null ? List.of("orders") : List.of("should-not-run");
        }, List::isEmpty);
        assertEquals(List.of("orders"), second);
        assertEquals(1, catalogCalls.get(), "schema layout must not retry catalog");
        assertEquals(2, schemaCalls.get());
    }

    @Test
    void bothEmptyDoesNotLockTheLayout() throws Exception {
        JdbcMetadataLayout layout = new JdbcMetadataLayout();
        layout.read("empty_db", (catalog, schema) -> List.of(), List::isEmpty);
        assertTrue(layout.slot().isEmpty(), "an empty database is not a schema server");

        List<String> later = layout.read("empty_db", (catalog, schema) ->
                catalog != null ? List.of("users") : List.of(), List::isEmpty);
        assertEquals(List.of("users"), later);
        assertEquals(JdbcMetadataLayout.Slot.CATALOG, layout.slot().orElseThrow());
    }

    @Test
    void probeDoesNotLearnFromAHit() throws Exception {
        JdbcMetadataLayout layout = new JdbcMetadataLayout();
        layout.probe("app", (catalog, schema) ->
                catalog != null ? List.of("id") : List.of(), List::isEmpty);
        assertTrue(layout.slot().isEmpty(), "PK/FK misses must not freeze layout");
    }

    @Test
    void rememberFromCatalogListingSkipsLaterSchemaProbes() throws Exception {
        JdbcMetadataLayout layout = new JdbcMetadataLayout();
        layout.remember(JdbcMetadataLayout.Slot.CATALOG);
        AtomicInteger schemaCalls = new AtomicInteger();
        layout.read("app", (catalog, schema) -> {
            if (schema != null) {
                schemaCalls.incrementAndGet();
            }
            return new ArrayList<String>();
        }, List::isEmpty);
        assertEquals(0, schemaCalls.get());
    }

    @Test
    void clearForgetsTheSlot() throws Exception {
        JdbcMetadataLayout layout = new JdbcMetadataLayout();
        layout.remember(JdbcMetadataLayout.Slot.CATALOG);
        layout.clear();
        assertTrue(layout.slot().isEmpty());
    }

    private static void count(
            String catalog, String schema, AtomicInteger catalogCalls, AtomicInteger schemaCalls) {
        if (catalog != null) {
            catalogCalls.incrementAndGet();
        }
        if (schema != null) {
            schemaCalls.incrementAndGet();
        }
    }
}
