package searchengine.controllers;

import com.github.tsohr.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.interfaces.IndexingService;
import searchengine.services.interfaces.SearchService;
import searchengine.services.interfaces.StatisticsService;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;

    private final SearchService searchService;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService, SearchService searchService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.searchService = searchService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<IndexingResponse> startIndexing() {
        return ResponseEntity.ok(indexingService.startIndexing());
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<IndexingResponse> stopIndexing() {
        return ResponseEntity.ok(indexingService.stopIndexing());
    }

    @PostMapping("/indexPage")
    public ResponseEntity<IndexingResponse> indexPage(@RequestParam String url) {
        return ResponseEntity.ok(indexingService.indexPage(url));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String query,
                                    @RequestParam(required = false) String site,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "20") int limit) {

        JSONObject response = new JSONObject();
        if (query == null || query.isBlank()) {
            response.put("result", false);
            response.put("error", "Задан пустой поисковый запрос");
            return new ResponseEntity<>(response.toString(), HttpStatus.BAD_REQUEST);
        }
        return ResponseEntity.ok(searchService.search(query, site, offset, limit));
    }

    @RequestMapping("/*")
    public ResponseEntity<?> pageNotFound() {

        JSONObject response = new JSONObject();
        response.put("result", false);
        response.put("error", "Указанная страница не найдена");
        return new ResponseEntity<>(response.toString(), HttpStatus.NOT_FOUND);
    }
}
