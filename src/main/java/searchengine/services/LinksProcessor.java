package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.Site;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveTask;

@RequiredArgsConstructor
public class LinksProcessor extends RecursiveTask<List<String>> {
    private final String rootURL;
    private final String URL;
    private final Site site;
    private final PageProcessor pageProcessor;
    private final List<String> result;
    private final boolean indexOnlyOnePage;

    @Override
    protected List<String> compute() {


        if (!MainProcessor.isIndexing && !indexOnlyOnePage) {
            return result;
        }

//        try {
//            Thread.sleep(1000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }

        String validURL = rootURL + URL;
        String urlPath = URL;
        if (!rootURL.equals(site.getUrl()) && URL.equals("/")) {
            urlPath = rootURL;
        }
        if (!pageProcessor.existsByPathAndSite(urlPath) || indexOnlyOnePage) {
            Document doc = pageProcessor.getDocFromUrl(validURL);
            if (doc != null) {
                pageProcessor.indexPage(doc, indexOnlyOnePage);
                result.add(urlPath);
                if (!indexOnlyOnePage) {
                    Elements elements = doc.select("a");
                    forkLinks(elements);
                }
            }
        }
        return result;
    }

    private void forkLinks(Elements elements) {

        List<LinksProcessor> tasks = new ArrayList<>();
        for (Element element : elements) {
            String path = element.attr("abs:href");
            String purePath = pageProcessor.deletePrefix(path);
            String pureSiteUrl = pageProcessor.deletePrefix(site.getUrl());
            if (!purePath.startsWith(pureSiteUrl)) {
                continue;
            }
            String href = purePath.replaceAll(pureSiteUrl, "");
            if (!href.contains("?")
                    && !href.contains("#")
                    && !href.isEmpty()
                    && (!href.contains(":"))
                    && !pageProcessor.existsByPathAndSite(href)) {
                LinksProcessor task;
                task = new LinksProcessor(rootURL, href, site, pageProcessor, result, indexOnlyOnePage);
                task.fork();
                tasks.add(task);
            }
        }
    }

    public Site getSite(){ return site;}
}

