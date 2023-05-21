package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import searchengine.config.JsoupConnect;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.model.StatusType;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Log4j2
public class MainProcessor extends Thread {
    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    public static boolean isIndexing;
    private final JsoupConnect connect;
    private final HashMap<LinksProcessor, ForkJoinPool> linksList = new HashMap<>();

    private void checkIndexingFinished() {
        boolean isIndexingDone = false;
        do {
            isIndexingDone = true;
            try {
                TimeUnit.SECONDS.sleep(1);
                for (Map.Entry<LinksProcessor, ForkJoinPool> entry : linksList.entrySet()) {
                    LinksProcessor links = entry.getKey();
                    if (!poolIsDone(links.getSite().getUrl())) {
                        isIndexingDone = false;
                        break;
                    }
                }
                if (isIndexingDone) {
                    isIndexing = false;
                    log.info("Indexing finished");
                    for (Map.Entry<LinksProcessor, ForkJoinPool> entry : linksList.entrySet()) {
                        LinksProcessor links = entry.getKey();
                        if (links.isDone()) {
                            ForkJoinPool pool = entry.getValue();
                            if (pool.getActiveThreadCount() == 0) {
                                pool.shutdown();
                                List<String> results;
                                results = links.join();
                                log.info("There are {} pages indexed for site: {}", results.size(), links.getSite().getUrl());
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } while (!isIndexingDone);
    }


    @Override
    public void run() {

        checkIndexingFinished();

    }

    private SiteEntity saveSite(Site site, String lastError, StatusType status) {

        SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
        if (siteEntity == null) {
            siteEntity = new SiteEntity();
            siteEntity.setName(site.getName());
            siteEntity.setUrl(site.getUrl());
            siteEntity.setLastError(lastError);
        }
        siteEntity.setStatus(status);
        siteEntity.setStatusTime(LocalDateTime.now());
        siteEntity.setLastError(lastError.isEmpty() ? siteEntity.getLastError() : lastError);
        siteRepository.save(siteEntity);
        return siteEntity;
    }

    public void startIndexing() {
        if (!isIndexing) {
            isIndexing = true;
            linksList.clear();
            List<Site> sitesList = sites.getSites();
            for (int i = 0; i < sitesList.size(); i++) {
                ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors() / sitesList.size());
                Site site = sitesList.get(i);
                SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
                if (siteEntity != null) {
                    log.info("Deleting data for site: {}", site.getUrl());
                    int siteId = siteEntity.getId();
                    List<PageEntity> pageEntities = siteEntity.getPageEntities();
                    if (!pageEntities.isEmpty()) {
                        indexRepository.deleteAllBySite(siteId);
                    }
                    pageRepository.deleteBySite(siteId);
                    lemmaRepository.deleteAllBySite(siteId);
                    siteRepository.delete(siteEntity);
                }
                indexSite(site, pool, false);
            }
            log.info("Indexing started");
        }
    }

    private void indexSite(Site site, ForkJoinPool pool, boolean indexOnlyOnePage) {
        SiteEntity siteEntity = saveSite(site, "", StatusType.INDEXING);
        PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, connect);
        List<String> result = new ArrayList<>();
        LinksProcessor links = new LinksProcessor(site.getUrl(), "/", site, pageProcessor, result, indexOnlyOnePage);
        linksList.put(links, pool);
        pool.execute(links);
    }

    public void stopIndexing() {
        isIndexing = false;
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
            if (siteEntity != null) {
                if (siteEntity.getStatus() != StatusType.INDEXED) {
                    do {
                        try {
                            TimeUnit.SECONDS.sleep(2);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        log.info("Waiting while data finished collect for site: {}", site.getUrl());
                    } while (!poolIsDone(site.getUrl()));
                    saveSite(site, "Индексация остановлена пользователем", StatusType.FAILED);
                }
            }
        }
        log.info("Indexing stopped");
    }

    private boolean poolIsDone(String url) {
        boolean poolIsDone = false;
        String pureSitePath = PageProcessor.deletePrefix(url);
        for (Map.Entry<LinksProcessor, ForkJoinPool> entry : linksList.entrySet()) {
            LinksProcessor links = entry.getKey();
            String currentSiteUrl = links.getSite().getUrl();
            if (PageProcessor.deletePrefix(currentSiteUrl).startsWith(pureSitePath)) {
                ForkJoinPool pool = entry.getValue();
                if (pool.getActiveThreadCount() == 0) {
                    poolIsDone = true;
                    break;
                }
            }
        }
        return poolIsDone;
    }

    public boolean indexPage(String url) {
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            if (PageProcessor.deletePrefix(url)
                    .startsWith(PageProcessor.deletePrefix(site.getUrl()))) {
                if (!isIndexing) {
                    isIndexing = true;
                    SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
                    linksList.clear();
                    List<String> result = new ArrayList<>();
                    PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, connect);
                    LinksProcessor links = new LinksProcessor(url, "", site, pageProcessor, result, true);
                    ForkJoinPool pool = new ForkJoinPool();
                    linksList.put(links, pool);
                    pool.execute(links);
                }
                return true;
            }
        }
        return false;
    }
}
