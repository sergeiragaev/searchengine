package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;

@Repository
@Transactional()
public interface PageRepository extends JpaRepository<PageEntity, Integer> {
    PageEntity findByPathAndSite(String path, SiteEntity site);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM `page` WHERE site_id = :siteId", nativeQuery = true)
    void deleteBySite(int siteId);
    boolean existsByPathAndSite(String path, SiteEntity site);

}