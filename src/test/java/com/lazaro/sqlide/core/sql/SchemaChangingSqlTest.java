package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaChangingSqlTest {

    @Test
    @DisplayName("CREATE TABLE / VIEW / PROCEDURE / FUNCTION change the schema")
    void createObjectChangesSchema() {
        assertTrue(SchemaChangingSql.changesSchema("CREATE TABLE faction (id INT)"));
        assertTrue(SchemaChangingSql.changesSchema("create or replace view v_active as select 1"));
        assertTrue(SchemaChangingSql.changesSchema("""
                CREATE DEFINER=`root`@`localhost` PROCEDURE greet()
                BEGIN
                    SELECT 1;
                END
                """));
        assertTrue(SchemaChangingSql.changesSchema("CREATE FUNCTION fn() RETURNS INT RETURN 1"));
        assertTrue(SchemaChangingSql.changesSchema("CREATE INDEX idx_name ON t(id)"));
        assertTrue(SchemaChangingSql.changesSchema("CREATE DATABASE warcraft"));
    }

    @Test
    @DisplayName("ALTER / DROP / RENAME of schema objects change the schema")
    void alterDropRenameChangeSchema() {
        assertTrue(SchemaChangingSql.changesSchema("ALTER TABLE faction ADD COLUMN name VARCHAR(64)"));
        assertTrue(SchemaChangingSql.changesSchema("DROP VIEW IF EXISTS v_active"));
        assertTrue(SchemaChangingSql.changesSchema("DROP PROCEDURE greet"));
        assertTrue(SchemaChangingSql.changesSchema("RENAME TABLE a TO b"));
        assertTrue(SchemaChangingSql.anyChangesSchema(List.of("SELECT 1", "CREATE TABLE t (id INT)")));
    }

    @Test
    @DisplayName("queries and DML do not trigger a schema refresh")
    void dmlDoesNotChangeSchema() {
        assertFalse(SchemaChangingSql.changesSchema("SELECT * FROM faction"));
        assertFalse(SchemaChangingSql.changesSchema("INSERT INTO faction VALUES (1)"));
        assertFalse(SchemaChangingSql.changesSchema("CALL greet()"));
        assertFalse(SchemaChangingSql.changesSchema("USE warcraft"));
        assertFalse(SchemaChangingSql.changesSchema("CREATE USER 'ada'@'%' IDENTIFIED BY 'x'"));
        assertFalse(SchemaChangingSql.changesSchema(null));
        assertFalse(SchemaChangingSql.changesSchema(""));
        assertFalse(SchemaChangingSql.anyChangesSchema(List.of("SELECT 1", "UPDATE t SET a = 1")));
    }

    @Test
    @DisplayName("DELIMITER and routine DDL are client/routine SQL")
    void clientOrRoutineSql() {
        assertTrue(SchemaChangingSql.isClientOrRoutineSql("DELIMITER $$"));
        assertTrue(SchemaChangingSql.isClientOrRoutineSql("CREATE PROCEDURE greet() BEGIN SELECT 1; END"));
        assertTrue(SchemaChangingSql.isClientOrRoutineSql("CREATE FUNCTION fn() RETURNS INT RETURN 1"));
        assertTrue(SchemaChangingSql.isClientOrRoutineSql("CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW SET NEW.id = 1"));
        assertTrue(SchemaChangingSql.isClientOrRoutineSql("CALL greet()"));
        assertFalse(SchemaChangingSql.isClientOrRoutineSql("SELECT * FROM faction"));
        assertFalse(SchemaChangingSql.isClientOrRoutineSql("CREATE TABLE race (id INT)"));
    }

    @Test
    @DisplayName("leading comments do not hide a CREATE")
    void commentsAreSkipped() {
        assertTrue(SchemaChangingSql.changesSchema("-- build the roster\nCREATE TABLE race (id INT)"));
        assertTrue(SchemaChangingSql.changesSchema("/* tmp */ CREATE VIEW v AS SELECT 1"));
    }
}
