package com.example.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MilvusLexicalClientTest {
    @Test void filterBindsOwnerAndExactSourceVersionPairsIncludingEmptyScope() {
        var scope=new KnowledgeQueryScope(7L, Map.of(9L,new KnowledgeQueryScope.Generation(11L,1),
                10L,new KnowledgeQueryScope.Generation(12L,2)));
        assertEquals("owner_id == 7 and ((source_id == 9 and version_id == 11) or (source_id == 10 and version_id == 12))",
                MilvusLexicalClient.filter(scope));
        assertTrue(MilvusLexicalClient.filter(new KnowledgeQueryScope(7L,Map.of())).contains("source_id == -1"));
        assertThrows(IllegalArgumentException.class,()->MilvusLexicalClient.filter(new KnowledgeQueryScope(null,Map.of())));
    }

    @Test void backendReceiptDoesNotCarryOverAcrossCollectionOrEndpoint() {
        var first=new MilvusLexicalClient(true,"http://localhost:19531/","","first",1000);
        assertEquals(first.backendKey(),new MilvusLexicalClient(true,"http://localhost:19531","secret","first",1000).backendKey());
        assertNotEquals(first.backendKey(),new MilvusLexicalClient(true,"http://localhost:19531","","second",1000).backendKey());
        assertNotEquals(first.backendKey(),new MilvusLexicalClient(true,"http://localhost:19532","","first",1000).backendKey());
    }

    @Test void transportFailureIsBoundedAndDisabledClientCannotWrite() {
        var unreachable=new MilvusLexicalClient(true,"http://127.0.0.1:1","","test",200);
        assertThrows(IllegalStateException.class,unreachable::prepare);
        assertThrows(IllegalStateException.class,()->new MilvusLexicalClient(false,"http://127.0.0.1:1","","test",200).upsert(List.of()));
    }

    /** Opt-in real Milvus contract; CI without Milvus skips this single integration test. */
    @Test @EnabledIfEnvironmentVariable(named="MILVUS_TEST_URL",matches=".+")
    void nativeChineseBm25SupportsUpsertOwnerDirectoryVersionAndEnglishTerms() {
        var client=new MilvusLexicalClient(true,System.getenv("MILVUS_TEST_URL"),"",
                "lexical_test_"+Long.toUnsignedString(System.nanoTime()),10000);
        client.prepare();
        var allowed=new MilvusLexicalClient.Document(UUID.randomUUID().toString(),7L,9L,11L,
                "缓存击穿使用互斥锁保护数据库。HashMap 使用哈希表，GC Roots 用于可达性分析。");
        var forbidden=new MilvusLexicalClient.Document(UUID.randomUUID().toString(),8L,9L,11L,allowed.text());
        var old=new MilvusLexicalClient.Document(UUID.randomUUID().toString(),7L,9L,10L,allowed.text());
        var otherDirectory=new MilvusLexicalClient.Document(UUID.randomUUID().toString(),7L,10L,12L,allowed.text());
        client.upsert(List.of(allowed,forbidden,old,otherDirectory));
        var scope=new KnowledgeQueryScope(7L,Map.of(9L,new KnowledgeQueryScope.Generation(11L,1)));
        assertEquals(1,client.count(scope));
        for (String query:List.of("缓存击穿如何保护数据库","hashmap","GC Roots")) {
            var hits=client.search(query,scope,20);
            assertEquals(List.of(allowed.id()),hits.stream().map(MilvusLexicalClient.Hit::id).toList(),query);
            assertTrue(hits.getFirst().score()>0);
        }
        client.upsert(List.of(allowed));
        assertEquals(1,client.count(scope));
        assertTrue(client.search("完全无关的天体物理星系",scope,20).isEmpty());
        assertTrue(client.search("缓存",new KnowledgeQueryScope(7L,Map.of()),20).isEmpty());
    }
}
