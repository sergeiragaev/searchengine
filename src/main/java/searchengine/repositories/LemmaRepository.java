package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.LemmaEntity;
import searchengine.model.SiteEntity;

@Repository
@Transactional()
public interface LemmaRepository extends JpaRepository<LemmaEntity, Integer> {
    LemmaEntity findLemmaEntityByLemmaAndSite(String lemma, SiteEntity site);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE lemma SET frequency=:newFrequency WHERE id = :lemmaId", nativeQuery = true)
    void updateFrequencyByLemma(int lemmaId, int newFrequency);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM `lemma` WHERE site_id = :siteId", nativeQuery = true)
    void deleteAllBySite(int siteId);

    int countLemmaEntitiesBySite(SiteEntity siteEntity);
}