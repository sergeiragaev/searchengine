package searchengine.services.implementations;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.search.DetailedSearchItem;
import searchengine.dto.search.ErrorSearchResponse;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.search.SuccessResponse;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.LemmaFinder;
import searchengine.services.interfaces.SearchService;

import java.io.IOException;
import java.util.*;

@Service
@Log4j2
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private final SitesList sites;

    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;
    @Autowired
    private final IndexRepository indexRepository;

    private List<SiteEntity> siteEntities;
    private Set<String> lemmas;
    private LemmaFinder lemmaFinder;
    private final List<DetailedSearchItem> detailedData = new ArrayList<>();

    private String query = "";
    private String site = "";

    private static final int SNIPPED_CHARS_COUNT = 200;

    private static final int MAX_PAGES_COUNT = 500;

    private int limit;
    private int offset;
    private int count = 0;

    private Float maxRank = 0F;
    private Map<PageEntity, Float> sortedPagesMap = new HashMap<>();


    @Override
    public SearchResponse search(String query, String site, int offset, int limit) {
        this.offset = offset;
        this.limit = limit;

        siteEntities = createSiteEntityList(site);

        if (site == null) {
            site = "";
        }

        if (!this.query.equals(query) || !this.site.equals(site)) {
            this.query = query;
            this.site = site;
            sortedPagesMap.clear();
            detailedData.clear();
            maxRank = 0F;
        }

        createData();

        if (detailedData.isEmpty()) {
            log.info("No pages found");
            ErrorSearchResponse response = new ErrorSearchResponse();
            response.setError("По вашему запросу ничего не найдено.");
            response.setResult(false);
            return response;
        } else {
            if (count == MAX_PAGES_COUNT) {
                log.info("Showing {} pages of more then {} found, offset {} ({})", detailedData.size(), count, offset, query);
            } else if (detailedData.size() < count) {
                log.info("Showing {} pages of {} found, offset {} ({})", detailedData.size(), count, offset, query);
            } else {
                log.info("Found {} pages ({})", detailedData.size(), query);
            }
            SuccessResponse successResponse = new SuccessResponse();
            successResponse.setResult(true);
            successResponse.setData(detailedData);
            successResponse.setCount(count);
            return successResponse;
        }
    }

    private void createData() {
        if (sortedPagesMap.isEmpty()) {
            try {
                lemmaFinder = LemmaFinder.getInstance();
                lemmas = lemmaFinder.getLemmaSet(query);

                createSortedPagesMap();

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            detailedData.clear();
            createDetailedData();
        }
    }

    private void createSortedPagesMap() {
        Map<LemmaEntity, Integer> lemmaFrequencyMap = createLemmaFrequencyMap();
        List<SiteEntity> checkedSites = new ArrayList<>();
        HashSet<PageEntity> foundPages = new HashSet<>();
        Map<LemmaEntity, Integer> sortedLemmaMap = (Map<LemmaEntity, Integer>) sortValues(lemmaFrequencyMap, false);
        for (LemmaEntity lemmaEntity : sortedLemmaMap.keySet()) {
            SiteEntity currentSiteEntity = lemmaEntity.getSite();
            if (checkedSites.contains(currentSiteEntity)) {
                continue;
            }
            checkedSites.add(currentSiteEntity);
            foundPages.addAll(collectPages(lemmaEntity));
        }
        count = foundPages.size();
        sortPagesByRank(foundPages);
    }

    private Map<LemmaEntity, Integer> createLemmaFrequencyMap() {

        Map<LemmaEntity, Integer> lemmaFrequencyMap = new HashMap<>();

        for (SiteEntity currentSiteEntity : siteEntities) {
            if (!containsAllLemmas(currentSiteEntity)) {
                continue;
            }
            for (String lemma : lemmas) {
                LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, currentSiteEntity);
                if (lemmaEntity != null) {
                    lemmaFrequencyMap.put(lemmaEntity, lemmaEntity.getFrequency());
                }
            }
        }
        return lemmaFrequencyMap;
    }

    private void sortPagesByRank(HashSet<PageEntity> foundPages) {
        HashMap<PageEntity, Float> rankedPages = new HashMap<>();
        for (PageEntity page : foundPages) {
            maxRank = Math.max(addToRankedPages(page, rankedPages), maxRank);
        }
        sortedPagesMap = (Map<PageEntity, Float>) sortValues(rankedPages, true);
        createDetailedData();
    }

    private void createDetailedData() {
        int i = 0;
        for (PageEntity page : sortedPagesMap.keySet()) {
            if (i < offset) {
                i++;
                continue;
            }
            float relevance = sortedPagesMap.get(page) / maxRank;
            addToDetailedList(page, relevance);
            if (detailedData.size() == limit) {
                break;
            }
        }
    }

    private List<SiteEntity> createSiteEntityList(String site) {

        SiteEntity siteEntity = null;
        if (site != null) {
            siteEntity = siteRepository.findByUrl(site);
        }

        siteEntities = new ArrayList<>();
        if (siteEntity != null) {
            siteEntities.add(siteEntity);
        } else {
            List<Site> sitesList = sites.getSites();
            for (Site siteToLook : sitesList) {
                SiteEntity currentSiteEntity = siteRepository.findByUrl(siteToLook.getUrl());
                if (currentSiteEntity != null) {
                    siteEntities.add(currentSiteEntity);
                }
            }
        }
        return siteEntities;
    }

    private float addToRankedPages(PageEntity page, Map<PageEntity, Float> rankedPages) {
        float result = 0F;
        for (String lemma : lemmas) {
            SiteEntity siteEntity = page.getSite();
            LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, siteEntity);
            if (lemmaEntity != null) {
                List<IndexEntity> indexEntityList = indexRepository.searchTop1000ByPageAndLemmaOrderByRankDesc(page, lemmaEntity);
                for (IndexEntity index : indexEntityList) {
                    if (!rankedPages.containsKey(page)) {
                        float newValue = index.getRank();
                        rankedPages.put(page, newValue);
                        result = Math.max(result, newValue);
                    } else {
                        float oldValue = rankedPages.get(page);
                        float newValue = oldValue + index.getRank();
                        rankedPages.replace(page, newValue);
                        result = Math.max(result, newValue);
                    }
                }
            }
        }
        return result;
    }

    private boolean containsAllLemmas(SiteEntity currentSiteEntity) {
        for (String lemma : lemmas) {
            LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, currentSiteEntity);
            if (lemmaEntity == null) {
                return false;
            }
        }
        return true;
    }

    private Set<PageEntity> collectPages(LemmaEntity lemmaEntity) {

        HashSet<PageEntity> foundPages = new HashSet<>();
        List<IndexEntity> indexEntityList = indexRepository.searchTop1000ByLemmaOrderByRankDesc(lemmaEntity);
        HashSet<PageEntity> firstPages = new HashSet<>();
        for (IndexEntity index : indexEntityList) {
            firstPages.add(index.getPage());
        }
        if (!firstPages.isEmpty()) {
            Set<String> nextLemmas = new HashSet<>(lemmas);
            nextLemmas.remove(lemmaEntity.getLemma());
            Set<PageEntity> mergePages = mergePages(nextLemmas, firstPages, lemmaEntity.getSite());
            foundPages.addAll(mergePages);
        }
        return foundPages;
    }

    private Set<PageEntity> mergePages(Set<String> lemmas,
                                       Set<PageEntity> pages, SiteEntity siteEntity) {
        if (lemmas.isEmpty()) {
            return pages;
        }
        HashSet<PageEntity> mergePages = new HashSet<>();
        String lemma = lemmas.stream().toList().get(0);
        LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, siteEntity);
        if (lemmaEntity == null) {
            return mergePages;
        }
        List<IndexEntity> indexEntityList = indexRepository.searchTop1000ByLemmaOrderByRankDesc(lemmaEntity);
        for (IndexEntity index : indexEntityList) {
            PageEntity page = index.getPage();
            if (pages.contains(page)) {
                mergePages.add(page);
            }
        }
        if (!mergePages.isEmpty()) {
            Set<String> nextLemmas = new HashSet<>(lemmas);
            nextLemmas.remove(lemma);
            return mergePages(nextLemmas, mergePages, siteEntity);
        } else {
            return mergePages;
        }
    }

    private Map<?, ?> sortValues(Map<?, ?> originalMap, boolean reverse) {
        {
            var list = new LinkedList(originalMap.entrySet());
            if (reverse) {
                list.sort((o1, o2) -> ((Comparable) (((float) ((Map.Entry) (o1)).getValue() * 100_000 +
                        ((PageEntity) ((Map.Entry) (o1)).getKey()).getId()) * -1))
                        .compareTo((((float) ((Map.Entry) (o2)).getValue() * 100_000 +
                                ((PageEntity) ((Map.Entry) (o2)).getKey()).getId()) * -1)));
            } else {
                //Custom Comparator
                list.sort((o1, o2) -> ((Comparable) ((Map.Entry) (o1)).getValue()).compareTo(((Map.Entry) (o2)).getValue()));
            }

            HashMap sortedHashMap = new LinkedHashMap();
            for (Object o : list) {
                Map.Entry entry = (Map.Entry) o;
                sortedHashMap.put(entry.getKey(), entry.getValue());
            }
            return sortedHashMap;
        }
    }

    private void addToDetailedList(PageEntity page, float relevance) {
        SiteEntity siteEntity = page.getSite();
        DetailedSearchItem item = new DetailedSearchItem();
        item.setSite(siteEntity.getUrl());
        item.setSiteName(siteEntity.getName());
        item.setUri(page.getPath());
        Document doc = Jsoup.parse(page.getContent());
        String title = doc.title();
        item.setTitle(title);
        String text = Jsoup.clean(doc.text(), Safelist.simpleText())
                .replaceAll("\\s+", " ");
        String snippet = createSnippet(text);
        String snippedWithBoldLemmas = addBoldToSnippet(snippet);
        item.setSnippet(snippedWithBoldLemmas);
        item.setRelevance(relevance);
        detailedData.add(item);
    }

    private String addBoldToSnippet(String text) {
        String[] snippetWords = text
                .trim().split("\\s");
        StringBuilder result = new StringBuilder();
        for (String snippetWord : snippetWords) {
            Set<String> lemmaSnippet = lemmaFinder.getLemmaSet(snippetWord);
            boolean haveToBold = false;
            for (String keySnippet : lemmaSnippet) {
                for (String lemmaFromQuery : lemmas) {
                    if (keySnippet.equals(lemmaFromQuery)) {
                        haveToBold = true;
                        break;
                    }
                }
                if (haveToBold) {
                    break;
                }
            }
            result.append(haveToBold ? "<b> " : " ")
                    .append(snippetWord)
                    .append(haveToBold ? "</b> " : " ");
        }
        return result.toString();
    }

    private String createSnippet(String text) {

        String dottedText = text
                .replaceAll("[\\s,:;!?'\"\\d]", ".");

        String[] textWords = dottedText
                .trim().split("[.]");
        int start = -1;
        for (String word : textWords) {
            Set<String> lemmaWords = lemmaFinder.getLemmaSet(word);
            for (String lemmaWord : lemmaWords) {
                if (lemmas.contains(lemmaWord)) {
                    start = dottedText.indexOf(word + ".");
                    break;
                }
            }
            if (start > -1) break;
        }
        String textToFindStart = dottedText.replaceAll("[А-ЯЁ\"«]", "!");

        for (; start > 0; start--) {
            if (textToFindStart.substring(start).startsWith(".!")) {
                start++;
                break;
            }
        }
        int end = Math.min(start + SNIPPED_CHARS_COUNT, text.length() - 1);
        for (; end < text.length(); end++) {
            if (textToFindStart.substring(end).startsWith(".!")) {
                break;
            }
        }

        return text.substring(Math.max(0, start), end);
    }
}
