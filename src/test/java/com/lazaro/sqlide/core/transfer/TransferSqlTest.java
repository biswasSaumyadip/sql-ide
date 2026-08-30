package com.lazaro.sqlide.core.transfer;

import org.junit.jupiter.api.Test;

import com.lazaro.sqlide.core.db.ConnectionConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferSqlTest {

    @Test
    void buildsInsertSelectWithQualifiedNames() {
        TransferRequest request = sampleRequest();
        String sql = TransferSql.insertSelect(request);
        assertEquals(
                "INSERT INTO `sales`.`orders` (`id`, `customer`) SELECT `order_id`, `cust_name` FROM `legacy`.`old_orders`",
                sql);
    }

    @Test
    void buildsStreamingStatements() {
        TransferRequest request = sampleRequest();
        assertEquals(
                "SELECT `order_id`, `cust_name` FROM `legacy`.`old_orders`",
                TransferSql.selectMapped(request));
        assertEquals(
                "INSERT INTO `sales`.`orders` (`id`, `customer`) VALUES (?, ?)",
                TransferSql.insertPlaceholders(request));
    }

    @Test
    void sameServerUsesDirectStrategy() {
        TransferRequest request = sampleRequest();
        assertEquals(TransferRequest.Strategy.SAME_CONNECTION, request.resolveStrategy());
    }

    @Test
    void differentHostsUseStreamingStrategy() {
        ConnectionConfig source = ConnectionConfig.mysql("localhost", 3306, "a", "u", "p");
        ConnectionConfig target = ConnectionConfig.mysql("remote", 3306, "b", "u", "p");
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("id", "id");
        TransferRequest request = new TransferRequest(
                source, "a", "t1", List.of("id"),
                target, "b", "t2", mapping,
                false, TransferRequest.ErrorHandling.ABORT, 1000, 10);
        assertEquals(TransferRequest.Strategy.CROSS_CONNECTION, request.resolveStrategy());
        assertTrue(TransferSql.truncate(request).contains("TRUNCATE"));
    }

    private static TransferRequest sampleRequest() {
        ConnectionConfig config = ConnectionConfig.mysql("localhost", 3306, "sales", "root", "secret");
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("order_id", "id");
        mapping.put("cust_name", "customer");
        return new TransferRequest(
                config,
                "legacy",
                "old_orders",
                List.of("order_id", "cust_name"),
                config,
                "sales",
                "orders",
                mapping,
                false,
                TransferRequest.ErrorHandling.ABORT,
                1000,
                100);
    }
}
