package com.quantboard.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移脚本切句器。DbService 手写的轻量 Flyway 依赖它把 .sql 拆成可逐条 execute 的语句，
 * 切错了就是启动时 DB 初始化失败 → 整个持久层静默降级（看板还能看，但历史/持仓全丢）。
 *
 * 真实案例：V12__cn_names.sql 里有一行 `PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;`，
 * 按行尾分号切的实现会把三句当成一句，JDBC 直接报语法错。
 */
class SplitSqlTest {

    @Test
    @DisplayName("普通多行多语句")
    void plain() {
        List<String> s = DbService.splitSql("""
                CREATE TABLE a (id INT);
                CREATE TABLE b (id INT);
                """);
        assertEquals(2, s.size());
        assertEquals("CREATE TABLE a (id INT)", s.get(0));
        assertEquals("CREATE TABLE b (id INT)", s.get(1));
    }

    @Test
    @DisplayName("行注释整行丢弃，不糊进语句")
    void comments() {
        List<String> s = DbService.splitSql("""
                -- 建表
                CREATE TABLE a (
                  id INT
                );
                -- 尾注释
                """);
        assertEquals(1, s.size());
        assertTrue(s.get(0).startsWith("CREATE TABLE a"));
        assertTrue(!s.get(0).contains("--"));
    }

    @Test
    @DisplayName("最后一句没有分号也要收进去")
    void noTrailingSemicolon() {
        List<String> s = DbService.splitSql("SELECT 1;\nSELECT 2");
        assertEquals(2, s.size());
        assertEquals("SELECT 2", s.get(1));
    }

    @Test
    @DisplayName("一行多句必须切开（V12 的 PREPARE/EXECUTE/DEALLOCATE）")
    void multipleStatementsOnOneLine() {
        List<String> s = DbService.splitSql(
                "PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;");
        assertEquals(3, s.size(), "一行三句必须拆成三条，否则 JDBC 报语法错：" + s);
        assertEquals("PREPARE st FROM @ddl", s.get(0));
        assertEquals("EXECUTE st", s.get(1));
        assertEquals("DEALLOCATE PREPARE st", s.get(2));
    }

    @Test
    @DisplayName("字符串字面量里的分号不能当分隔符")
    void semicolonInsideLiteral() {
        List<String> s = DbService.splitSql(
                "INSERT INTO t (v) VALUES ('a;b');\nUPDATE t SET v='x;y';");
        assertEquals(2, s.size());
        assertTrue(s.get(0).contains("'a;b'"));
        assertTrue(s.get(1).contains("'x;y'"));
    }

    @Test
    @DisplayName("空文本与纯注释返回空列表，不产生空语句")
    void emptyInput() {
        assertTrue(DbService.splitSql("").isEmpty());
        assertTrue(DbService.splitSql("-- 什么都没有\n\n").isEmpty());
    }

    @Test
    @DisplayName("真实迁移文件能被完整切分（回归：库里有几个 V 就有几份脚本）")
    void realMigrationFiles() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.dir"))
                .getParent().resolve("db/migration");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Path.of("db/migration");
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(dir),
                "找不到 db/migration，跳过（在 backend-java 目录下跑 mvn test 时可用）");

        try (var files = java.nio.file.Files.list(dir)) {
            for (var f : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).toList()) {
                List<String> stmts = DbService.splitSql(java.nio.file.Files.readString(f));
                assertTrue(stmts.size() >= 1, f.getFileName() + " 切出 0 条语句");
                for (String st : stmts) {
                    assertTrue(!st.isBlank(), f.getFileName() + " 切出了空语句");
                    assertTrue(!st.stripLeading().startsWith("--"), f.getFileName() + " 切出了注释行: " + st);
                }
            }
        }
    }
}
