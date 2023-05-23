package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import searchengine.config.JsoupConnect;
import searchengine.config.Site;
import searchengine.config.SitesList;
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
    private boolean indexOnlyOnePage;
    private boolean isDataDeleting;

    private void checkIsIndexingFinished() {
        boolean isIndexingDone;
        do {
            try {
                TimeUnit.SECONDS.sleep(5);
                isIndexingDone = shutdownPools();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } while (!isIndexingDone);
    }

    private boolean shutdownPools() {
        boolean isIndexingDone = !isDataDeleting;
        if (!isDataDeleting) {
            ArrayList<LinksProcessor> linksToRemove = new ArrayList<>();
            for (Map.Entry<LinksProcessor, ForkJoinPool> entry : linksList.entrySet()) {
                LinksProcessor links = entry.getKey();
                ForkJoinPool pool = entry.getValue();
                Site site = links.getSite();
                if (links.isDone() && pool.getActiveThreadCount() == 0) {
                    pool.shutdown();
                    saveInfoAndWriteLog(site);
                    linksToRemove.add(links);
                } else {
                    if (isStopped) {
                        log.info("Waiting while data finished collecting for site: {}", site.getUrl());
                    }
                    isIndexingDone = false;
                }
            }
            for (LinksProcessor links : linksToRemove){
                linksList.remove(links);
            }
            if (isIndexingDone) {
                isIndexing = false;
                if (!isStopped && !indexOnlyOnePage) {
                    log.info("Indexing finished");
                }
            }
        }
        return isIndexingDone;
    }

    private void saveInfoAndWriteLog(Site site) {
        int countPages = pageRepository.countPageEntitiesBySite(siteRepository.findByUrl(site.getUrl()));
        if (indexOnlyOnePage) {
            log.info("Adding/updating page finished");
            saveSite(site, "", null);
        } else if (isStopped) {
            saveSite(site, "Индексация остановлена пользователем", StatusType.FAILED);
        } else {
            saveSite(site, "", StatusType.INDEXED);
        }
        log.info("There are {} pages indexed for site: {}", countPages, site.getUrl());
    }


    @Override
    public void run() {

        checkIsIndexingFinished();

    }

    private SiteEntity saveSite(Site site, String lastError, StatusType status) {

        SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
        if (siteEntity == null) {
            siteEntity = new SiteEntity();
            siteEntity.setName(site.getName());
            siteEntity.setUrl(site.getUrl());
            siteEntity.setLastError(lastError);
            siteEntity.setStatus(StatusType.INDEXING);
        }
        if (status == null && siteEntity.getStatus() == StatusType.INDEXING) {
            status = StatusType.INDEXED;
        }
        siteEntity.setStatus((status == null) ? siteEntity.getStatus() : status);
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
                deleteDataFoSite(site);
                ForkJoinPool pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors() / sitesList.size());
                indexSite(site, pool);
            }
            log.info("Indexing started");
        }
    }

    private void deleteDataFoSite(Site site) {
        isDataDeleting = true;
        SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
        if (siteEntity != null) {
            log.info("Deleting data for site: {}", site.getUrl());
            int siteId = siteEntity.getId();
            indexRepository.deleteAllBySite(siteId);
            pageRepository.deleteBySite(siteId);
            lemmaRepository.deleteAllBySite(siteId);
            siteRepository.delete(siteEntity);
        }
        isDataDeleting = false;
    }

    private void indexSite(Site site, ForkJoinPool pool) {
        SiteEntity siteEntity = saveSite(site, "", StatusType.INDEXING);
        PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, connect);
        LinksProcessor links = new LinksProcessor(site.getUrl(), "/", site, pageProcessor, indexOnlyOnePage, new TreeSet<>());
        linksList.put(links, pool);
        pool.execute(links);
    }

    public void stopIndexing() {
        isIndexing = false;
        isStopped = true;
        log.info("Indexing stopped");
    }

    public boolean indexPage(String url) {
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            if (PageProcessor.deletePrefix(url)
                    .startsWith(PageProcessor.deletePrefix(site.getUrl()))) {
                if (!isIndexing) {
                    indexOnlyOnePage = true;
                    isIndexing = true;
                    SiteEntity siteEntity = saveSite(site, "", null);
                    linksList.clear();
                    PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, connect);
                    LinksProcessor links = new LinksProcessor(url, "", site, pageProcessor, indexOnlyOnePage, new TreeSet<>());
                    ForkJoinPool pool = new ForkJoinPool();
                    linksList.put(links, pool);
                    log.info("Adding/updating page {} started", url);
                    pool.execute(links);
                }
                return true;
            }
        }
        return false;
    }
}
