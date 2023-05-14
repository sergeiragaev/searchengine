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

    @Override
    public SearchResponse search(String query, String site, int offset, int limit) {

        SiteEntity siteEntity = null;
        if (site != null) {
            siteEntity = siteRepository.findByUrl(site);
        }

        List<DetailedSearchItem> detailedData = new ArrayList<>();

        try {
            LemmaFinder lemmaFinder = LemmaFinder.getInstance();
            Set<String> lemmas = lemmaFinder.getLemmaSet(query);
            Map<LemmaEntity, Integer> lemmaFrequencyMap = new HashMap<>();
            List<SiteEntity> siteEntities = new ArrayList<>();
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

            for (SiteEntity currentSiteEntity : siteEntities) {
                if (!containsAllLemmas(currentSiteEntity, lemmas)) {
                    continue;
                }
                for (String lemma : lemmas) {
                    LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, currentSiteEntity);
                    if (lemmaEntity != null) {
                        lemmaFrequencyMap.put(lemmaEntity, lemmaEntity.getFrequency());
                    }
                }
            }
            List<SiteEntity> checkedSites = new ArrayList<>();
            HashSet<PageEntity> foundPages = new HashSet<>();
            Map<LemmaEntity, Integer> sortedMap = (Map<LemmaEntity, Integer>) sortValues(lemmaFrequencyMap, false);
            for (LemmaEntity lemmaEntity : sortedMap.keySet()) {
                SiteEntity currentSiteEntity = lemmaEntity.getSite();
                if (checkedSites.contains(currentSiteEntity)) {
                    continue;
                }
                checkedSites.add(currentSiteEntity);
                foundPages.addAll(collectPages(lemmaEntity, lemmas));
            }
            HashMap<PageEntity, Float> rankedPages = new HashMap<>();
            Float maxRank = 0F;
            for (PageEntity page : foundPages) {
                maxRank = Math.max(addToRankedPages(page, rankedPages, lemmas), maxRank);
            }
            Map<PageEntity, Float> sortedPagesMap = (Map<PageEntity, Float>) sortValues(rankedPages, true);
//            int currentPage = 0;
            for (PageEntity page : sortedPagesMap.keySet()) {
                float relevance = sortedPagesMap.get(page) / maxRank;
//                currentPage++;
//                if (offset*limit <= currentPage && (offset + 1)*limit >= currentPage) {
                    addToDetailedList(page, detailedData, lemmaFinder, lemmas, relevance);
//                }
            }
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

    private float addToRankedPages(PageEntity page, Map<PageEntity, Float> rankedPages, Set<String> lemmas) {
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

    private boolean containsAllLemmas(SiteEntity currentSiteEntity, Set<String> lemmas) {
        for (String lemma : lemmas) {
            LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, currentSiteEntity);
            if (lemmaEntity == null) {
                return false;
            }
        }
        return true;
    }

    private Set<PageEntity> collectPages(LemmaEntity lemmaEntity,
                              Set<String> lemmas) {

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

    private void addToDetailedList(PageEntity page, List<DetailedSearchItem> detailed,
                                   LemmaFinder lemmaFinder, Set<String> lemmas, float relevance) {
//        PageEntity pageEntity = pageRepository.findById(pageId).get();
        SiteEntity siteEntity = page.getSite();
        DetailedSearchItem item = new DetailedSearchItem();
        item.setSite(siteEntity.getUrl());
        item.setSiteName(siteEntity.getName());
        item.setUri(page.getPath());
        Document doc = Jsoup.parse(page.getContent());
        String title = doc.title();
        item.setTitle(title);
        String text = doc.text()
//                .clean(content, Safelist.simpleText())
//                .replaceAll("[^А-Яа-яЁё\\d\\s,.!]+", " ")
                .replaceAll("\\s+", " ");

        String[] textWords = text
//                .replaceAll("[\\d\\s,.!]+", " ")
                .trim().split("\\s+");
        int start = 0;
        for (String word : textWords) {
            Set<String> lemmaWords = lemmaFinder.getLemmaSet(word);
            for (String lemmaWord : lemmaWords) {
                if (lemmas.contains(lemmaWord)) {
                    start = text.indexOf(word);
                    break;
                }
            }
        }
        String textToFindStart = text.replaceAll("[А-ЯЁ\"]", "!");

        for (;;) {
            if (start < 0
                    || textToFindStart.substring(start).startsWith(" !")) {
                start++;
                break;
            }
            start--;
        }

        int end = start + 200;
        for (;;) {
            if (text.length() <= end || textToFindStart.substring(end).startsWith(" !")) {
                end = Math.min(text.length(), end);
                break;
            }
            end++;
        }

        String snippet = text.substring(start, end);
        String[] snippetWords = snippet
//                .replaceAll("[\\d\\s,.!]+", " ")
                .trim().split("\\s+");
        String newSnipped = "";
        for (String snippetWord : snippetWords) {
            Set<String> lemmaSnippet = lemmaFinder.getLemmaSet(snippetWord);
            boolean haveToBold = false;
            for (String keySnippet : lemmaSnippet) {
                for (String key : lemmas) {
                    if (key.equals(keySnippet)) {
                        haveToBold = true;
                        break;
                    }
                }
            }
            newSnipped = newSnipped + (haveToBold ? "<b> " : " ") + snippetWord + (haveToBold ? "</b> " : " ");
        }
        item.setSnippet(newSnipped);
        item.setRelevance(relevance);
        detailed.add(item);
    }
}
