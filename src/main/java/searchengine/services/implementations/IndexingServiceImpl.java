package searchengine.services.implementations;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import searchengine.config.JsoupConnect;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.indexing.ErrorResponse;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.model.StatusType;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.LinksProcessor;
import searchengine.services.PageProcessor;
import searchengine.services.interfaces.IndexingService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Log4j2
public class IndexingServiceImpl implements IndexingService, CommandLineRunner {

    private final SitesList sites;
    public static final List<ForkJoinPool> poolList = new ArrayList<>();

    public static boolean isIndexing;

    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;
    @Autowired
    private final IndexRepository indexRepository;

    private final JsoupConnect connect;

    public IndexingResponse startIndexing() {

        isIndexing = false;
        for (ForkJoinPool pool : poolList){
            if (pool.getActiveThreadCount()!=0){
                isIndexing = true;
            }
        }

        if (!isIndexing) {
            isIndexing = true;
            List<Site> sitesList = sites.getSites();
            poolList.clear();
            for (Site site : sitesList) {
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
                indexSite(site, Runtime.getRuntime().availableProcessors()/sitesList.size());
            }
            return new IndexingResponse();
        } else {
            ErrorResponse response = new ErrorResponse();
            response.setError("Индексация уже запущена");
            return response;
        }
    }

    public IndexingResponse stopIndexing() {
        if (isIndexing) {
            isIndexing = false;
            List<Site> sitesList = sites.getSites();
            for (Site site : sitesList) {
                SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
                if (siteEntity != null) {
                    if (siteEntity.getStatus() != StatusType.INDEXED) {
                        saveSite(site, "Индексация остановлена пользователем", StatusType.FAILED);
                    }
                }
            }
            return new IndexingResponse();
        } else {
            ErrorResponse response = new ErrorResponse();
            response.setError("Индексация не запущена");
            return response;
        }
    }

    public IndexingResponse indexPage(String url) {
        Site site = null;
        List<Site> sitesList = sites.getSites();
        for (Site value : sitesList) {
            if (PageProcessor.deletePrefix(url)
                    .startsWith(PageProcessor.deletePrefix(value.getUrl()))) {
                site = value;
                break;
            }
        }
        if (site != null) {
            SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
            new Thread(new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, url, connect)).start();
//            pageProcessor.indexPage(url);
            return new IndexingResponse();
        } else {
            ErrorResponse response = new ErrorResponse();
            response.setError("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
            return response;
        }
    }


    private void indexSite(Site site, int parallelism) {
        SiteEntity siteEntity = saveSite(site, "", StatusType.INDEXING);
        PageProcessor pageProcessor = new PageProcessor(pageRepository, siteRepository, lemmaRepository, indexRepository, siteEntity, "", connect);
        LinksProcessor links = new LinksProcessor(site.getUrl(), "/", site, pageProcessor);
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        pool.execute(links);
        poolList.add(pool);
//        siteEntity.setStatus(StatusType.INDEXED);
//        siteEntity.setStatusTime(LocalDateTime.now());
//        siteRepository.save(siteEntity);
    }

    @Override
    public void run(String... args) throws Exception {
        do {
            try {
                TimeUnit.SECONDS.sleep(20);
                boolean allPoolsShutDown = true;
                List<Site> sitesList = sites.getSites();
                for (int i = 0; i < sitesList.size(); i++) {
//                    isIndexing = false;
                    ForkJoinPool pool = poolList.get(i);
                    Site site = sitesList.get(i);
                    SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
                    if (siteEntity != null) {
                        if (pool.getActiveThreadCount() == 0 && siteEntity.getStatus() == StatusType.INDEXING) {
                            pool.shutdown();
                            saveSite(site, "", StatusType.INDEXED);
                        }
                    }
                    allPoolsShutDown = allPoolsShutDown && pool.isShutdown();
                }
//                isIndexing = !allPoolsShutDown;
                if (allPoolsShutDown) log.info("Indexing finished");
            } catch (IndexOutOfBoundsException e) {
                log.info("Indexing not running");
            }
        } while (true);
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
            siteEntity.setLastError(lastError.isEmpty() ? siteEntity.getLastError() : lastError );
            siteRepository.save(siteEntity);
            return siteEntity;
        }
}
