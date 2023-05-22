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
    private boolean isStopped;

    private void checkIndexingFinished() {
        boolean isIndexingDone = false;
        do {
            isIndexingDone = true;
            try {
                TimeUnit.SECONDS.sleep(2);
                for (Map.Entry<LinksProcessor, ForkJoinPool> entry : linksList.entrySet()) {
                    LinksProcessor links = entry.getKey();
                    if (!poolIsDone(links.getSite().getUrl())) {
                        isIndexingDone = false;
                        break;
                    }
                }
                if (isIndexingDone) {
                    isIndexing = false;
                    if (!isStopped) {
                        log.info("Indexing finished");
                    }
                    for (Map.Entry<LinksProcessor, ForkJoinPool> entry : linksList.entrySet()) {
                        LinksProcessor links = entry.getKey();
                        ForkJoinPool pool = entry.getValue();
                        if (links.isDone() && pool.getActiveThreadCount() == 0) {
                            pool.shutdown();
                            TreeSet<String> results = links.join();
                            Site site = links.getSite();
                            log.info("There are {} pages indexed for site: {}", results.size(), site.getUrl());
                            if (!isStopped) {
                                saveSite(site, "", StatusType.INDEXED);
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
            for (Site site : sitesList) {
                ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors() / sitesList.size());
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
                indexSite(site, pool);
            }
            log.info("Indexing started");
        }
    }

    private void indexSite(Site site, ForkJoinPool pool) {
        SiteEntity siteEntity = saveSite(site, "", StatusType.INDEXING);
        PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, connect);
        TreeSet<String> listOfURL = new TreeSet<>();
        LinksProcessor links = new LinksProcessor(site.getUrl(), "/", site, pageProcessor, false, listOfURL);
        linksList.put(links, pool);
        pool.execute(links);
    }

    public void stopIndexing() {
        isIndexing = false;
        isStopped = true;
        boolean allPoolsDone = true;
        List<Site> sitesList = sites.getSites();
        do {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            allPoolsDone = true;
            for (Site site : sitesList) {
                SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
                if (siteEntity != null) {
                    if (siteEntity.getStatus() != StatusType.INDEXED) {
                        allPoolsDone = allPoolsDone && poolIsDone(site.getUrl());
                        if (allPoolsDone) {
                            saveSite(site, "Индексация остановлена пользователем", StatusType.FAILED);
                        } else {
                            log.info("Waiting while data finished collect for site: {}", site.getUrl());
                        }
                    }
                }
            }
        } while (!allPoolsDone);
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
                }
                break;
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
                    TreeSet<String> listOfURL = new TreeSet<>();
                    PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, connect);
                    LinksProcessor links = new LinksProcessor(url, "", site, pageProcessor, true, listOfURL);
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
