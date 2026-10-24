package ru.unknownsmp.zhora;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ZhoraTextTest {
    private static final java.util.regex.Pattern P = ZhoraText.mentionPattern(List.of("жора"));

    @Test void mentionDetected() {
        assertTrue(ZhoraText.mentions(P, "Жора, как дела?"));
        assertTrue(ZhoraText.mentions(P, "эй жора"));
        assertFalse(ZhoraText.mentions(P, "нажора нет"));
    }

    @Test void stripMention() {
        assertEquals("как дела?", ZhoraText.stripMention(P, "Жора, как дела?").trim());
    }

    @Test void classify() {
        assertEquals(ZhoraText.Intent.MUTE_ME, ZhoraText.classify("не подкалывай меня"));
        assertEquals(ZhoraText.Intent.MEMORY_STORE, ZhoraText.classify("запомни что я люблю арбузы"));
        assertEquals(ZhoraText.Intent.COMPLAINT, ZhoraText.classify("Steve читерит, проверь"));
        assertEquals(ZhoraText.Intent.HISTORY, ZhoraText.classify("кто вчера убил Bob?"));
        assertEquals(ZhoraText.Intent.GENERAL, ZhoraText.classify("привет"));
    }

    @Test void sanitizeStripsCodesAndLimits() {
        String s = ZhoraText.sanitize("§cПривет &aмир /op hack\n\nвторая строка", 20);
        assertFalse(s.contains("§"));
        assertFalse(s.contains("\n"));
        assertTrue(s.length() <= 20);
    }

    @Test void sanitizeNeverStartsWithSlash() {
        assertFalse(ZhoraText.sanitize("/op me", 100).startsWith("/"));
    }

    @Test void quoteUserTextTruncates() {
        assertTrue(ZhoraText.quoteUserText("a".repeat(500), 50).length() <= 55);
    }

    @Test void deathTypes() {
        assertEquals("death_lava", ZhoraText.deathType("LAVA", false));
        assertEquals("death_pvp", ZhoraText.deathType("ENTITY_ATTACK", true));
    }

    @Test void fallbackSubstitutes() {
        String s = ZhoraText.fallback("death_lava", Map.of("p", "Steve"), new Random(1));
        assertNotNull(s);
        assertFalse(s.contains("{p}"));
    }

    @Test void rangeYesterday() {
        long now = System.currentTimeMillis();
        var r = ZhoraText.parseRange("что было вчера", now, ZoneId.of("UTC"));
        assertTrue(r.from() < r.to());
        assertTrue(r.to() <= now);
    }

    @Test void stemMatchesDeclinedName() {
        assertEquals("alexdzuba812", ZhoraText.matchStem("alexdzuba812", List.of("alexdzuba812")));
    }
}
