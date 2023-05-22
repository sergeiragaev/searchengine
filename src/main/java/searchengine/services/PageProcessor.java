package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import searchengine.config.JsoupConnect;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.model.StatusType;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@RequiredArgsConstructor
@Log4j2
public class PageProcessor {
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final SiteEntity siteEntity;

    private final JsoupConnect connect;


    private PageEntity savePage(String path, int code, String content) {

        synchronized (Lock.class) {

            Lock readLock = rwLock.readLock();
            readLock.lock();
            try {
                if (siteEntity != null) {
                    PageEntity pageEntity = pageRepository.findByPathAndSite(path, siteEntity);
                    if (pageEntity == null) {
                        pageEntity = new PageEntity();
                        pageEntity.setPath(path);
                        pageEntity.setSite(siteEntity);
                    }
                    pageEntity.setCode(code);
                    pageEntity.setContent(content);
                    pageRepository.saveAndFlush(pageEntity);

                    siteEntity.setStatusTime(LocalDateTime.now());
                    siteRepository.save(siteEntity);

                    return pageEntity;
                } else {
                    return null;
                }
            } finally {
                readLock.unlock();
            }
        }
    }

    private boolean existsByPathAndSite(String path) {
        synchronized (Lock.class) {
            Lock writeLock = rwLock.writeLock();
            writeLock.lock();
            try {
                pageRepository.flush();
                PageEntity result = pageRepository.findByPathAndSite(path, siteEntity);
                return result != null;
            } finally {
                writeLock.unlock();
            }
        }
    }

    boolean existsByPath(String path){
        return existsByPathAndSite(path);
    }

    public void indexPage(Document doc, boolean removeOldText) {

        String urlPath = deletePrefix(doc.location())
                .replace(deletePrefix(siteEntity.getUrl()), "");
        if (!existsByPathAndSite(urlPath) || removeOldText) {
            String oldText = "";
            PageEntity pageEntity;
            if (removeOldText) {
                pageEntity = pageRepository.findByPathAndSite(urlPath, siteEntity);
                if (pageEntity != null) {
                    oldText = Jsoup.parse(pageEntity.getContent()).text()
                            .replaceAll("[^А-Яа-яЁё\\d\\s,.!]+", " ")
                            .replaceAll("\\s+", " ");
                }
            }
            log.info("Indexing page: {}", doc.location());
            pageEntity = savePage(urlPath, doc.connection().response().statusCode(), doc.outerHtml());
            LemmaProcessor lemmaProcessor = new LemmaProcessor(pageEntity, lemmaRepository, indexRepository, oldText,
                    doc.text(), siteEntity, urlPath);
            if (!oldText.isEmpty()) {
                lemmaProcessor.deleteLemmas();
            }
            lemmaProcessor.saveLemmas();
        }
    }

    public Document getDocFromUrl(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(connect.getUserAgent())
                    .referrer(connect.getReferrer())
                    .timeout(connect.getTimeout())
                    .ignoreHttpErrors(connect.isIgnoreHttpErrors())
                    .followRedirects(connect.isFollowRedirects())
                    .get();
        } catch (Exception e) {
            if (e.getClass().getName().contains("Timeout")) {
                siteEntity.setStatus(StatusType.FAILED);
                siteEntity.setLastError("Ошибка индексации: страница " + url + " недоступна");
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
            }
            return null;
        }
    }

    public static String deletePrefix(String path) {
        return path.toLowerCase()
                .replaceAll("https://www.", "")
                .replaceAll("http://www.", "")
                .replaceAll("https://", "")
                .replaceAll("http://", "");
    }
}
