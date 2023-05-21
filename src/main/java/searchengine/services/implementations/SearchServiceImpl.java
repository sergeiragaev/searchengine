package searchengine.services.implementations;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.LemmaFinder;
import searchengine.services.interfaces.SearchService;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private final SitesList sites;

    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;

    private List<SiteEntity> siteEntities;
    private Set<String> lemmas;
    private LemmaFinder lemmaFinder;
    private  List<DetailedSearchItem> detailedData;

    private static final int SNIPPED_CHARS_COUNT = 200;



    @Override
    public SearchResponse search(String query, String site, int offset, int limit) {

        siteEntities = createSiteEntityList(site);

        detailedData = new ArrayList<>();

        try {
            lemmaFinder = LemmaFinder.getInstance();
            lemmas = lemmaFinder.getLemmaSet(query);

            createdDetailedData();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (detailedData.isEmpty()) {
            ErrorSearchResponse response = new ErrorSearchResponse();
            response.setError("Страница с похожими словами не найдена");
            response.setResult(false);
            return response;
        } else {
            SuccessResponse successResponse = new SuccessResponse();
            successResponse.setResult(true);
            successResponse.setData(detailedData);
            successResponse.setCount(detailedData.size());
            return successResponse;
        }
    }

    private void createdDetailedData() {
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
        Float maxRank = 0F;
        HashMap<PageEntity, Float> rankedPages = new HashMap<>();
        for (PageEntity page : foundPages) {
            maxRank = Math.max(addToRankedPages(page, rankedPages), maxRank);
        }
        HashMap<PageEntity, Float> sortedPagesMap = (HashMap<PageEntity, Float>) sortValues(rankedPages, true);
        for (PageEntity page : sortedPagesMap.keySet()) {
            float relevance = sortedPagesMap.get(page) / maxRank;
            addToDetailedList(page, relevance);
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
            List<IndexEntity> indexEntityList = lemmaEntity.getIndexEntities();
            for (IndexEntity index : indexEntityList) {
                if (index.getPage().equals(page)) {
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
        List<IndexEntity> indexEntityList = lemmaEntity.getIndexEntities();
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
            return new HashSet<>();
        }
        List<IndexEntity> indexEntityList = lemmaEntity.getIndexEntities();
        for (IndexEntity index : indexEntityList) {
            PageEntity page = index.getPage();
            if (pages.contains(page)) {
                mergePages.add(page);
            }
        }
        Set<String> nextLemmas = new HashSet<>(lemmas);
        nextLemmas.remove(lemma);
        return mergePages(nextLemmas, mergePages, siteEntity);
    }

    private Map<?, ?> sortValues(Map<?, ?> lemmaFrequencyMap, boolean reverse) {
        {
            var list = new LinkedList(lemmaFrequencyMap.entrySet());
            //Custom Comparator
            list.sort((o1, o2) -> ((Comparable) ((Map.Entry) (o1)).getValue()).compareTo(((Map.Entry) (o2)).getValue())
                    * (reverse ? -1 : 1));

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
        String text = doc.text()
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
        String result = "";
        for (String snippetWord : snippetWords) {
            Set<String> lemmaSnippet = lemmaFinder.getLemmaSet(snippetWord);
            boolean haveToBold = false;
            for (String keySnippet : lemmaSnippet) {
                for (String lemmaFromQuery : lemmas) {
                    if (lemmaFromQuery.equals(keySnippet)) {
                        haveToBold = true;
                        break;
                    }
                }
            }
            result = result + (haveToBold ? "<b> " : " ") + snippetWord + (haveToBold ? "</b> " : " ");
        }
        return result;
    }

    private String createSnippet(String  text) {

        String[] textWords = text
                .trim().split("\\s");
        int start = -1;
        for (String word : textWords) {
            Set<String> lemmaWords = lemmaFinder.getLemmaSet(word);
            for (String lemmaWord : lemmaWords) {
                if (lemmas.contains(lemmaWord)) {
                    start = text.indexOf(word);
                    break;
                }
            }
            if (start > -1) break;
        }
        String textToFindStart = text.replaceAll("[А-ЯЁ\"«]", "!");

        for (; start > 0; start--) {
            if (textToFindStart.substring(start).startsWith(" !")) {
                start++;
                break;
            }
        }

        int end = start + SNIPPED_CHARS_COUNT;
        for (; end <= text.length(); end++) {
            if (textToFindStart.substring(end).startsWith(" !")) {
                break;
            }
        }

        return text.substring(start, end);
    }
}
