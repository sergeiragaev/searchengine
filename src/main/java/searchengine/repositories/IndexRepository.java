package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;

import java.util.List;

@Repository
@Transactional()
public interface IndexRepository extends JpaRepository<IndexEntity, Integer> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM `index` WHERE page_id = :pageId", nativeQuery = true)
    void deleteByPage(int pageId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM `index` WHERE page_id IN ( SELECT id FROM `page` WHERE site_id = :siteId)", nativeQuery = true)
    void deleteAllBySite(int siteId);

    List<IndexEntity> searchTop1000ByPageAndLemmaOrderByRankDesc(PageEntity page, LemmaEntity lemma);

    List<IndexEntity> searchTop1000ByLemmaOrderByRankDesc(LemmaEntity lemma);

}