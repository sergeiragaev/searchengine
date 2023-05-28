package searchengine.services;

import org.springframework.transaction.annotation.Transactional;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LemmaProcessor {

    PageEntity pageEntity;
    LemmaRepository lemmaRepository;
    IndexRepository indexRepository;

    String oldText;
    String newText;
    SiteEntity site;
    String urlPath;

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public LemmaProcessor(PageEntity pageEntity, LemmaRepository lemmaRepository, IndexRepository indexRepository,
                          String oldText, String newText, SiteEntity site, String urlPath) {
        this.pageEntity = pageEntity;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.oldText = oldText;
        this.newText = newText;
        this.site = site;
        this.urlPath = urlPath;
    }


    @Transactional
    public void saveLemmas() {
        try {
            LemmaFinder lemmaFinder = LemmaFinder.getInstance();
            indexRepository.deleteByPage(pageEntity.getId());
            Map<String, Integer> lemmas = lemmaFinder.collectLemmas(newText);
            for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
                String key = entry.getKey();
                int frequency = entry.getValue();
                LemmaEntity lemma = saveLemma(key);
                saveIndex(lemma, frequency);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveIndex(LemmaEntity lemma, int frequency) {
        IndexEntity indexEntity = new IndexEntity();
        indexEntity.setLemma(lemma);
        indexEntity.setPage(pageEntity);
        indexEntity.setRank(frequency);
        indexRepository.save(indexEntity);
    }

    private LemmaEntity saveLemma(String lemma) {
        LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, site);
        if (lemmaEntity == null) {
            lemmaEntity = new LemmaEntity();
        }
        lemmaEntity.setLemma(lemma);
        lemmaEntity.setFrequency(lemmaEntity.getFrequency() + 1);
        lemmaEntity.setSite(site);
        lemmaRepository.save(lemmaEntity);
        return lemmaEntity;
    }

    public void deleteLemmas() {
        try {
            LemmaFinder lemmaFinder = LemmaFinder.getInstance();
            Map<String, Integer> lemmas = lemmaFinder.collectLemmas(oldText);
            for (String key : lemmas.keySet()) {
                deleteLemma(key);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void deleteLemma(String lemma) {
        synchronized (Lock.class) {
            Lock readLock = rwLock.readLock();
            readLock.lock();
            LemmaEntity lemmaEntity = lemmaRepository.findLemmaEntityByLemmaAndSite(lemma, site);
            lemmaRepository.updateFrequencyByLemma(lemmaEntity.getId(), lemmaEntity.getFrequency() - 1);
            readLock.unlock();
        }
    }
}
