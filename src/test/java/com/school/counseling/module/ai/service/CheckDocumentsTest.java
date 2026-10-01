package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class CheckDocumentsTest {

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Test
    void inspectDatabaseKnowledge() {
        List<KnowledgeDocument> docs = documentRepository.findAll();
        System.out.println("TOTAL DOCUMENTS IN DB = " + docs.size());
        for (KnowledgeDocument d : docs) {
            System.out.println("DOC #" + d.getId() + " [" + d.getEffectiveYear() + "] " + d.getTitle() + " -> status=" + d.getStatus() + ", chunks=" + d.getTotalChunks());
        }

        var allChunks = chunkRepository.findAllActiveWithRelations();
        long regulationCount = allChunks.stream().filter(c -> "REGULATION".equalsIgnoreCase(c.getSourceType())).count();
        long faqCount = allChunks.stream().filter(c -> "FAQ_CHAT".equalsIgnoreCase(c.getSourceType())).count();
        System.out.println("TOTAL ACTIVE CHUNKS = " + allChunks.size());
        System.out.println("REGULATION CHUNKS = " + regulationCount);
        System.out.println("FAQ_CHAT CHUNKS = " + faqCount);
    }

    @Autowired
    private BatchDocumentIngestionService batchService;

    @Autowired
    private RagKnowledgeService ragService;

    @Autowired
    private RagChatbotService chatbotService;

    @Test
    void testIngestKey2026Docs() throws Exception {
        java.io.File folder2026 = new java.io.File("D:\\HK5\\CongNghePhanMem\\tailieuAI\\2026");
        if (folder2026.exists()) {
            java.io.File[] files = folder2026.listFiles((dir, name) -> name.endsWith(".pdf"));
            System.out.println("Found " + (files != null ? files.length : 0) + " PDFs in 2026 folder.");
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.getName().contains("1944") || f.getName().contains("261_SHDK") || f.getName().contains("AVDV")) {
                        System.out.println("Ingesting: " + f.getName());
                        int chunks = batchService.ingestSingleDocument(f, 2026);
                        System.out.println("Created chunks: " + chunks);
                    }
                }
            }
            ragService.reloadVectorCache();
        }
    }

    @Test
    void testAllUserQueries() throws Exception {
        ragService.reloadVectorCache();

        String[] queries = {
                "Nộp chứng chỉ tiếng Anh dot 1 va 2 thoi gian khi nao",
                "khi nao thi avdv nam hc 2026",
                "ke hoach sinh hoat dau nam hc thi sao",
                "giang vien khoa IT gom nhg nguoii nao"
        };

        for (String q : queries) {
            System.out.println("\n==========================================");
            System.out.println("QUERY: " + q);
            System.out.println("EXPANDED: " + AcademicAbbreviationUtils.expand(q));
            var res = chatbotService.ask(q, null);
            System.out.println("Primary source: " + res.getPrimarySourceType());
            System.out.println("Confidence: " + res.getConfidenceScore());
            System.out.println("Needs warning: " + res.isNeedsHistoricalWarning());
            System.out.println("Matched chunks count: " + (res.getMatchedChunks() != null ? res.getMatchedChunks().size() : 0));
            if (res.getMatchedChunks() != null) {
                for (var m : res.getMatchedChunks()) {
                    System.out.println("  -> [" + m.getSourceType() + " | Year " + m.getEffectiveYear() + " | Score " + m.getSimilarityScore() + "] " + m.getTitle());
                }
            }
            System.out.println("ANSWER:\n" + res.getAnswer());
        }
    }
}
