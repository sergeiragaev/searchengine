package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.Site;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.RecursiveTask;

@Log4j2
@RequiredArgsConstructor
public class LinksProcessor extends RecursiveTask<TreeSet<String>> {
    private final String rootURL;
    private final String URL;
    private final Site site;
    private final PageProcessor pageProcessor;
    private final boolean indexOnlyOnePage;
    private final TreeSet<String> listOfURL;

    @Override
    protected TreeSet<String> compute() {

        if (!MainProcessor.isIndexing && !indexOnlyOnePage) {
            return listOfURL;
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        List<LinksProcessor> tasks = new ArrayList<>();

        String validURL = rootURL + URL;
        String urlPath = URL;
        if (!rootURL.equals(site.getUrl()) && URL.equals("/")) {
            urlPath = rootURL;
        }

        if (pageProcessor.existsByPath(urlPath) && !indexOnlyOnePage) {
            return listOfURL;
        }
        if (!listOfURL.add(validURL)) {
            return listOfURL;
        }


        Document doc = pageProcessor.getDocFromUrl(validURL);
        if (doc != null) {
//            log.info(urlPath);
            pageProcessor.indexPage(doc, indexOnlyOnePage);
            if (!indexOnlyOnePage) {
                Elements elements = doc.select("a");
                forkLinks(elements, tasks);
            }
        }
        return listOfURL;
    }

    private void forkLinks(Elements elements, List<LinksProcessor> tasks) {

        for (Element element : elements) {
            String path = element.attr("abs:href");
            String purePath = PageProcessor.deletePrefix(path);
            String pureSiteUrl = PageProcessor.deletePrefix(site.getUrl());
            if (!purePath.startsWith(pureSiteUrl)) {
                continue;
            }
            String href = purePath.replaceAll(pureSiteUrl, "");
            if (!href.contains("?")
                    && !href.contains("#")
                    && !href.isEmpty()
                    && !href.contains(":")
                    && !href.endsWith(".pdf")
                    && !href.endsWith(".jpg")
                    && !href.endsWith(".jpeg")
                    && !href.endsWith(".png")) {
                LinksProcessor task;
                task = new LinksProcessor(rootURL, href, site, pageProcessor, indexOnlyOnePage, listOfURL);
                task.fork();
                tasks.add(task);
            }
        }
    }

    public Site getSite() {
        return site;
    }
}

