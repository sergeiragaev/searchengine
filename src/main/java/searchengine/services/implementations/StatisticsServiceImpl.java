package searchengine.services.implementations;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteEntity;
import searchengine.repositories.SiteRepository;
import searchengine.services.interfaces.StatisticsService;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SitesList sites;

    @Autowired
    private final SiteRepository siteRepository;

    @Override
    public StatisticsResponse getStatistics() {
//        String[] statuses = { "INDEXED", "FAILED", "INDEXING" };
//        String[] errors = {
//                "Ошибка индексации: главная страница сайта не доступна",
//                "Ошибка индексации: сайт не доступен",
//                ""
//        };

        String status = "N/A";
        String error = "";
        long millis = 0L;

        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(true);

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            int pages = 0;
            int lemmas = 0;
            SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl());
            if (siteEntity != null) {
                pages = siteEntity.getPageEntities().size();
                lemmas = siteEntity.getLemmaEntities().size();
                status = siteEntity.getStatus().toString();
                error = siteEntity.getLastError();
                millis = siteEntity.getStatusTime().toEpochSecond(ZoneOffset.of("+3")) * 1000;
            }
            item.setPages(pages);
            item.setLemmas(lemmas);
            item.setStatus(status);
            item.setError(error);
            item.setStatusTime(millis);
            total.setPages(total.getPages() + pages);
            total.setLemmas(total.getLemmas() + lemmas);
            detailed.add(item);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }
}
