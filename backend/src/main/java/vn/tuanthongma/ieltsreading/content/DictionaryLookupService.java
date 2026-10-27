package vn.tuanthongma.ieltsreading.content;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
class DictionaryLookupService {
    private static final String PROVIDER = "MERRIAM_WEBSTER_LEARNERS";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final RestClient restClient;
    private final TransactionTemplate transactions;
    private final String apiKey;

    record RelatedWord(String word, String relationType) {}
    record Sense(long id, String partOfSpeech, String phoneticText, String audioUrl, String definition, String example,
                 List<RelatedWord> relatedWords) {}
    record Entry(long id, String word, String sourceUrl, List<Sense> senses) {}

    private record ProviderPronunciation(String text, String audioId, String audioUrl) {}
    private record ProviderMeaning(String definition, String example, List<RelatedWord> relatedWords) {}
    private record ProviderEntry(String providerId, String word, String partOfSpeech, List<String> stems,
                                 List<ProviderPronunciation> pronunciations, List<ProviderMeaning> meanings) {}

    DictionaryLookupService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            @Value("${dictionary.merriam-webster.api-key:}") String apiKey) {
        this.jdbc = jdbc;
        this.restClient = RestClient.create();
        this.transactions = new TransactionTemplate(transactionManager);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    List<Entry> lookup(String query) {
        String term = normalize(query);
        if (term.isBlank() || term.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Từ tra cứu không hợp lệ.");
        List<Entry> cached = readByTerm(term);
        if (!cached.isEmpty()) return cached;
        if (apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "API từ điển chưa được cấu hình.");

        String responseBody;
        try {
            URI uri = UriComponentsBuilder.newInstance().scheme("https").host("www.dictionaryapi.com")
                    .path("/api/v3/references/learners/json/{word}").queryParam("key", "{key}")
                    .buildAndExpand(Map.of("word", term, "key", apiKey)).encode().toUri();
            responseBody = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Không thể kết nối tới từ điển lúc này.");
        }

        String providerMessage = responseBody == null ? "" : responseBody.strip();
        if (providerMessage.toLowerCase(Locale.ROOT).contains("invalid api key")
                && providerMessage.toLowerCase(Locale.ROOT).contains("not subscribed")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "API key chưa được cấp quyền cho Merriam-Webster Learner’s Dictionary. Hãy chọn đúng Learners Dictionary trong My Keys rồi cập nhật key trong file .env.");
        }
        JsonNode response;
        try {
            response = JSON_MAPPER.readTree(responseBody);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Từ điển trả về dữ liệu không hợp lệ.");
        }

        List<ProviderEntry> providerEntries = parse(response);
        if (providerEntries.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy từ này trong từ điển.");
        List<Entry> saved = transactions.execute(status -> save(providerEntries, term));
        return saved == null ? List.of() : saved;
    }

    private List<Entry> save(List<ProviderEntry> providerEntries, String requestedTerm) {
        LinkedHashSet<Long> entryIds = new LinkedHashSet<>();
        for (ProviderEntry source : providerEntries) {
            String normalizedWord = normalize(source.word());
            jdbc.update("""
                    INSERT INTO vocabulary_entries (word, normalized_word, language_code, provider, provider_entry_id, source_url)
                    VALUES (?, ?, 'en', ?, ?, ?)
                    ON CONFLICT (language_code, normalized_word) DO NOTHING
                    """, source.word(), normalizedWord, PROVIDER, source.providerId(), sourceUrl(source.word()));
            Long entryId = jdbc.queryForObject("""
                    SELECT vocabulary_entry_id FROM vocabulary_entries
                    WHERE language_code = 'en' AND normalized_word = ? FOR UPDATE
                    """, Long.class, normalizedWord);
            if (entryId == null) continue;
            entryIds.add(entryId);

            boolean hasSenses = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM vocabulary_senses WHERE vocabulary_entry_id = ? AND provider_entry_id = ?)", Boolean.class, entryId, source.providerId()));
            if (!hasSenses) {
                int order = 1;
                List<ProviderPronunciation> pronunciations = source.pronunciations().isEmpty()
                        ? java.util.Collections.singletonList(null) : source.pronunciations();
                for (ProviderMeaning meaning : source.meanings()) {
                    long meaningId = insertMeaning(meaning, source.providerId(), order);
                    for (ProviderPronunciation pronunciation : pronunciations) {
                        Long phoneticId = pronunciation == null ? null : insertPhonetic(pronunciation);
                        jdbc.update("""
                                INSERT INTO vocabulary_senses (vocabulary_entry_id, vocabulary_phonetic_id, vocabulary_meaning_id, part_of_speech, provider_entry_id, display_order)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON CONFLICT DO NOTHING
                                """, entryId, phoneticId, meaningId, source.partOfSpeech(), source.providerId(), order);
                    }
                    for (RelatedWord related : meaning.relatedWords()) {
                        jdbc.update("""
                                INSERT INTO vocabulary_related_words (vocabulary_meaning_id, related_word, normalized_related_word, relation_type)
                                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                                """, meaningId, related.word(), normalize(related.word()), related.relationType());
                    }
                    order++;
                }
            }

            List<String> terms = new ArrayList<>(source.stems());
            terms.add(source.word());
            terms.add(requestedTerm);
            for (String alias : terms) {
                if (!normalize(alias).isBlank()) jdbc.update("""
                        INSERT INTO vocabulary_entry_search_terms (language_code, normalized_term, vocabulary_entry_id)
                        VALUES ('en', ?, ?) ON CONFLICT DO NOTHING
                        """, normalize(alias), entryId);
            }
        }
        return readEntryIds(List.copyOf(entryIds));
    }

    private long insertMeaning(ProviderMeaning meaning, String providerId, int order) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO vocabulary_meanings (definition_text, example, provider_definition_id) VALUES (?, ?, ?)", new String[]{"vocabulary_meaning_id"});
            statement.setString(1, meaning.definition()); statement.setString(2, meaning.example()); statement.setString(3, providerId + ":" + order); return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Could not save dictionary meaning");
        return keys.getKey().longValue();
    }

    private long insertPhonetic(ProviderPronunciation pronunciation) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO vocabulary_phonetics (phonetic_text, audio_url, provider_audio_id) VALUES (?, ?, ?)", new String[]{"vocabulary_phonetic_id"});
            statement.setString(1, pronunciation.text()); statement.setString(2, pronunciation.audioUrl()); statement.setString(3, pronunciation.audioId()); return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("Could not save dictionary pronunciation");
        return keys.getKey().longValue();
    }

    private List<Entry> readByTerm(String term) {
        List<Long> ids = jdbc.queryForList("""
                SELECT DISTINCT e.vocabulary_entry_id FROM vocabulary_entry_search_terms st
                JOIN vocabulary_entries e ON e.vocabulary_entry_id = st.vocabulary_entry_id
                WHERE st.language_code = 'en' AND st.normalized_term = ?
                ORDER BY e.vocabulary_entry_id
                """, Long.class, term);
        if (ids.isEmpty()) ids = jdbc.queryForList("SELECT vocabulary_entry_id FROM vocabulary_entries WHERE language_code = 'en' AND normalized_word = ?", Long.class, term);
        if (ids.isEmpty()) return List.of();
        jdbc.update("INSERT INTO vocabulary_entry_search_terms (language_code, normalized_term, vocabulary_entry_id) VALUES ('en', ?, ?) ON CONFLICT DO NOTHING", term, ids.getFirst());
        return readEntryIds(ids);
    }

    private List<Entry> readEntryIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<MutableEntry> entries = jdbc.query("SELECT vocabulary_entry_id, word, source_url FROM vocabulary_entries WHERE vocabulary_entry_id IN (" + placeholders + ") ORDER BY word",
                (r, n) -> new MutableEntry(r.getLong(1), r.getString(2), r.getString(3)), ids.toArray());
        Map<Long, MutableEntry> byId = new LinkedHashMap<>(); entries.forEach(entry -> byId.put(entry.id, entry));
        jdbc.query("""
                SELECT s.vocabulary_sense_id, s.vocabulary_entry_id, s.part_of_speech,
                       p.phonetic_text, p.audio_url, m.definition_text, m.example,
                       rw.related_word, rw.relation_type
                FROM vocabulary_senses s
                JOIN vocabulary_meanings m ON m.vocabulary_meaning_id = s.vocabulary_meaning_id
                LEFT JOIN vocabulary_phonetics p ON p.vocabulary_phonetic_id = s.vocabulary_phonetic_id
                LEFT JOIN vocabulary_related_words rw ON rw.vocabulary_meaning_id = m.vocabulary_meaning_id
                WHERE s.vocabulary_entry_id IN (""" + placeholders + ") ORDER BY s.display_order, s.vocabulary_sense_id",
                r -> {
                    MutableEntry entry = byId.get(r.getLong("vocabulary_entry_id"));
                    if (entry == null) return;
                    long senseId = r.getLong("vocabulary_sense_id");
                    MutableSense sense = entry.senses.get(senseId);
                    if (sense == null) {
                        sense = new MutableSense(senseId, r.getString("part_of_speech"), r.getString("phonetic_text"),
                                r.getString("audio_url"), r.getString("definition_text"), r.getString("example"));
                        entry.senses.put(senseId, sense);
                    }
                    String related = r.getString("related_word");
                    if (related != null) sense.related.put(related + "|" + r.getString("relation_type"), new RelatedWord(related, r.getString("relation_type")));
                }, ids.toArray());
        return entries.stream().map(e -> new Entry(e.id, e.word, e.sourceUrl, e.senses.values().stream().map(MutableSense::toRecord).toList())).toList();
    }

    private static final class MutableEntry {
        final long id; final String word; final String sourceUrl; final Map<Long, MutableSense> senses = new LinkedHashMap<>();
        MutableEntry(long id, String word, String sourceUrl) { this.id = id; this.word = word; this.sourceUrl = sourceUrl; }
    }
    private static final class MutableSense {
        final long id; final String partOfSpeech; final String phoneticText; final String audioUrl; final String definition; final String example; final Map<String, RelatedWord> related = new LinkedHashMap<>();
        MutableSense(long id, String pos, String phoneticText, String audioUrl, String definition, String example) { this.id=id; this.partOfSpeech=pos; this.phoneticText=phoneticText; this.audioUrl=audioUrl; this.definition=definition; this.example=example; }
        Sense toRecord() { return new Sense(id, partOfSpeech, phoneticText, audioUrl, definition, example, List.copyOf(related.values())); }
    }

    private List<ProviderEntry> parse(JsonNode root) {
        if (root == null || !root.isArray()) return List.of();
        List<ProviderEntry> result = new ArrayList<>();
        for (JsonNode item : root) {
            String providerId = text(item.path("meta").path("id"));
            String word = text(item.path("hwi").path("hw")).replace("*", "").strip();
            String partOfSpeech = text(item.path("fl"));
            if (providerId.isBlank() || word.isBlank() || partOfSpeech.isBlank()) continue;
            List<String> stems = strings(item.path("meta").path("stems"));
            List<ProviderPronunciation> pronunciations = new ArrayList<>();
            JsonNode prs = item.path("hwi").path("prs");
            if (prs.isArray()) for (JsonNode pronunciation : prs) {
                String phonetic = text(pronunciation.path("ipa"));
                String audioId = text(pronunciation.path("sound").path("audio"));
                if (!phonetic.isBlank() || !audioId.isBlank()) pronunciations.add(new ProviderPronunciation(phonetic.isBlank() ? null : phonetic, audioId.isBlank() ? null : audioId, audioId.isBlank() ? null : audioUrl(audioId)));
            }
            List<String> definitions = strings(item.path("shortdef"));
            if (definitions.isEmpty()) definitions = textDefinitions(item.path("def"));
            List<String> examples = collectTextByKey(item.path("def"), "vis");
            List<String> synonyms = collectTextByKey(item.path("def"), "syn_list");
            List<String> antonyms = collectTextByKey(item.path("def"), "ant_list");
            List<ProviderMeaning> meanings = new ArrayList<>();
            for (int i = 0; i < definitions.size(); i++) {
                String definition = cleanDefinition(definitions.get(i));
                if (definition.isBlank()) continue;
                List<RelatedWord> related = new ArrayList<>();
                synonyms.stream().limit(12).forEach(wordItem -> related.add(new RelatedWord(wordItem, "SYNONYM")));
                antonyms.stream().limit(12).forEach(wordItem -> related.add(new RelatedWord(wordItem, "ANTONYM")));
                meanings.add(new ProviderMeaning(definition, i < examples.size() ? examples.get(i) : null, related));
            }
            if (!meanings.isEmpty()) result.add(new ProviderEntry(providerId, word, partOfSpeech, stems, pronunciations, meanings));
        }
        return result;
    }

    private static List<String> textDefinitions(JsonNode def) {
        List<String> result = new ArrayList<>();
        collectDefinitionText(def, result);
        return result;
    }

    private static void collectDefinitionText(JsonNode node, List<String> output) {
        if (node == null) return;
        if (node.isArray()) {
            if (node.size() >= 2 && "text".equals(text(node.get(0))) && node.get(1).isTextual()) {
                String clean = cleanDefinition(node.get(1).asText());
                if (!clean.isBlank()) output.add(clean);
                return;
            }
            for (JsonNode child : node) collectDefinitionText(child, output);
        } else if (node.isObject()) node.properties().forEach(field -> collectDefinitionText(field.getValue(), output));
    }

    private static List<String> collectTextByKey(JsonNode node, String key) {
        List<String> result = new ArrayList<>();
        collect(node, key, result);
        return result.stream().map(String::strip).filter(s -> !s.isBlank()).distinct().toList();
    }

    private static void collect(JsonNode node, String key, List<String> output) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode value = node.get(key);
            if (value != null) collectValue(value, output);
            node.properties().forEach(field -> { if (!field.getKey().equals(key)) collect(field.getValue(), key, output); });
        } else if (node.isArray()) for (JsonNode child : node) collect(child, key, output);
    }

    private static void collectValue(JsonNode value, List<String> output) {
        if (value.isTextual()) output.add(value.asText());
        else if (value.isArray()) for (JsonNode child : value) {
            if (child.isTextual()) output.add(child.asText());
            else if (child.isObject() && child.has("t")) output.add(text(child.path("t")));
            else collectValue(child, output);
        }
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode child : node) if (child.isTextual() && !child.asText().isBlank()) values.add(child.asText());
        return values;
    }

    private static String cleanDefinition(String value) {
        return value.replaceAll("\\{[^}]+}", " ").replaceAll("\\s+", " ").replaceAll("\\s+([,.;:])", "$1").strip();
    }

    private static String text(JsonNode node) { return node != null && node.isTextual() ? node.asText() : ""; }
    private static String normalize(String value) { return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String sourceUrl(String word) { return "https://www.merriam-webster.com/dictionary/" + java.net.URLEncoder.encode(word, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static String audioUrl(String audioId) {
        String lower = audioId.toLowerCase(Locale.ROOT);
        String folder = lower.startsWith("bix") ? "bix" : lower.startsWith("gg") ? "gg" : Character.isDigit(lower.charAt(0)) ? "number" : lower.substring(0, 1);
        return "https://media.merriam-webster.com/audio/prons/en/us/mp3/" + folder + "/" + audioId + ".mp3";
    }
}
