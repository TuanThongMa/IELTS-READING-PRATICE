package vn.tuanthongma.ieltsreading.content;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryLookupServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void keepsOneUsageExampleWithItsOwnMeaningAndExcludesPhraseMatches() {
        var entries = DictionaryLookupService.parse(JSON.readTree("""
                [
                  {
                    "meta":{"id":"authority","stems":["authority","authorities"]},
                    "hwi":{"hw":"au*thor*i*ty"},"fl":"noun",
                    "shortdef":["the power to direct","a respected quality","a quality that gives credibility"],
                    "def":[{"sseq":[
                      [["sense",{"dt":[["text","{bc}the power to direct"],["vis",[{"t":"She has {it}authority{/it} to decide."}]]]}]],
                      [["sen",{"sn":"2"}],
                       ["sense",{"dt":[["text","{bc}a respected quality"]]}],
                       ["sense",{"dt":[["text","{bc}a quality that gives credibility"],["vis",[{"t":"His words carried {it}authority{/it}."}]]]}]]
                    ]}]
                  },
                  {"meta":{"id":"figure:1","stems":["figure","authority figure"]},"hwi":{"hw":"fig*ure"},"fl":"noun","shortdef":["a number"]},
                  {"meta":{"id":"moral:1","stems":["moral","moral authority"]},"hwi":{"hw":"mor*al"},"fl":"noun","shortdef":["a lesson"]},
                  {"meta":{"id":"local authority","stems":["local authority"]},"hwi":{"hw":"local authority"},"fl":"noun","shortdef":["local government"]}
                ]
                """));

        var authority = DictionaryLookupService.matchingEntries("authority", entries);
        assertEquals(1, authority.size());
        assertEquals("authority", authority.getFirst().word());
        assertEquals("She has authority to decide.", authority.getFirst().meanings().get(0).example());
        assertNull(authority.getFirst().meanings().get(1).example());
        assertEquals("His words carried authority.", authority.getFirst().meanings().get(2).example());
        assertEquals("authority", DictionaryLookupService.matchingEntries("authorities", entries).getFirst().word());
        assertEquals("local authority", DictionaryLookupService.matchingEntries("local authority", entries).getFirst().word());
        assertTrue(DictionaryLookupService.matchingEntries("authority figure", entries).isEmpty());
    }

    @Test
    void putsExactHeadwordBeforeAValidInflectedForm() {
        var entries = DictionaryLookupService.parse(JSON.readTree("""
                [
                  {"meta":{"id":"see","stems":["see","saw"]},"hwi":{"hw":"see"},"fl":"verb","shortdef":["to notice"]},
                  {"meta":{"id":"saw","stems":["saw","saws"]},"hwi":{"hw":"saw"},"fl":"noun","shortdef":["a cutting tool"]}
                ]
                """));

        var matches = DictionaryLookupService.matchingEntries("saw", entries);
        assertEquals(2, matches.size());
        assertEquals("saw", matches.get(0).word());
        assertEquals("see", matches.get(1).word());
    }
}
