package com.llmgateway;

import com.llmgateway.config.HostPort;
import com.llmgateway.routing.AccountPool;
import com.llmgateway.routing.CandidateRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigAndSeedTest {

    @Test
    void parsesHostAndPort() {
        assertEquals(new HostPort("140.143.144.152", 3306), HostPort.parse("140.143.144.152:3306", 1));
        assertEquals(new HostPort("140.143.144.152", 6379), HostPort.parse("140.143.144.152", 6379));
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse(":", 6379));
    }

    @Test
    void groupsFlatRowsWithoutReordering() {
        CandidateRow first = row(20, 2, "b");
        CandidateRow second = row(20, 9, "c");
        CandidateRow third = row(5, 1, "a");
        var accounts = AccountPool.group(List.of(first, second, third));
        assertEquals(20, accounts.get(0).accountId());
        assertEquals(List.of(2, 9), accounts.get(0).keys().stream().map(key -> key.keyId()).toList());
        assertEquals(5, accounts.get(1).accountId());
    }

    @Test
    void catalogMapperDoesNotWriteAndApplicationDoesNotRunSchema() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapper/ModelCatalogMapper.xml")).toLowerCase();
        assertFalse(xml.contains("<insert"));
        assertFalse(xml.contains("<update"));
        assertFalse(xml.contains("<delete"));

        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            List<String> offenders = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            String text = Files.readString(path);
                            return text.contains("schema.sql") || text.contains("DROP TABLE");
                        } catch (Exception ex) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertEquals(List.of(), offenders);
        }
    }

    @Test
    void trackedSourcesDoNotContainSecrets() throws Exception {
        String apiKeyMarker = "sk-" + "ws-";
        String passwordMarker = "207710" + "cwl";
        List<Path> roots = List.of(Path.of("src/main"), Path.of("pom.xml"), Path.of("openspec"), Path.of("sql"));
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        String text = Files.readString(path);
                        assertFalse(text.contains(apiKeyMarker), path.toString());
                        assertFalse(text.contains(passwordMarker), path.toString());
                    } catch (Exception ex) {
                        throw new IllegalStateException(path.toString(), ex);
                    }
                });
            }
        }
    }

    private static CandidateRow row(int accountId, int keyId, String apiKey) {
        CandidateRow row = new CandidateRow();
        row.setAccountId(accountId);
        row.setKeyId(keyId);
        row.setApiKey(apiKey);
        return row;
    }

}
