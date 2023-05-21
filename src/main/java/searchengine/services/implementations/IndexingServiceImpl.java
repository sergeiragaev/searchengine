package searchengine.services.implementations;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.JsoupConnect;
import searchengine.config.SitesList;
import searchengine.dto.indexing.ErrorResponse;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.MainProcessor;
import searchengine.services.interfaces.IndexingService;


@Service
@RequiredArgsConstructor
@Log4j2
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sites;
    @Autowired
    private final SiteRepository siteRepository;
    @Autowired
    private final PageRepository pageRepository;
    @Autowired
    private final LemmaRepository lemmaRepository;
    @Autowired
    private final IndexRepository indexRepository;
    private final JsoupConnect connect;

    private MainProcessor mainProcessor;

    public IndexingResponse startIndexing() {

        if (!mainProcessor.isIndexing) {
            mainProcessor = new MainProcessor(sites, siteRepository, pageRepository, lemmaRepository,indexRepository, connect);
            mainProcessor.start();
            mainProcessor.startIndexing();
            return new IndexingResponse();
        } else {
            ErrorResponse response = new ErrorResponse();
            response.setError("Индексация уже запущена");
            return response;
        }
    }

    public IndexingResponse stopIndexing() {
        if (mainProcessor.isIndexing) {
            mainProcessor.stopIndexing();
            return new IndexingResponse();
        } else {
            ErrorResponse response = new ErrorResponse();
            response.setError("Индексация не запущена");
            return response;
        }
    }

    public IndexingResponse indexPage(String url) {
        if (!mainProcessor.isIndexing) {
            mainProcessor = new MainProcessor(sites, siteRepository, pageRepository, lemmaRepository, indexRepository, connect);
            mainProcessor.start();
            if (mainProcessor.indexPage(url)) {
                return new IndexingResponse();
            } else {
                ErrorResponse response = new ErrorResponse();
                response.setError("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
                return response;
            }
        } else {
            ErrorResponse response = new ErrorResponse();
            response.setError("Индексация уже запущена");
            return response;
        }
    }
}
