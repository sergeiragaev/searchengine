package searchengine.services;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.Site;
import searchengine.services.implementations.IndexingServiceImpl;

import java.io.IOException;
import java.util.concurrent.RecursiveTask;

public class LinksProcessor extends RecursiveTask<Boolean> {
    private final String rootURL;
    private final String URL;
    private final Site site;
    private final PageProcessor pageProcessor;

    public LinksProcessor(String rootURL, String URL, Site site, PageProcessor pageProcessor) {
        this.rootURL = rootURL;
        this.URL = URL;
        this.site = site;
        this.pageProcessor = pageProcessor;
    }

    @Override
    protected Boolean compute() {

        if (!IndexingServiceImpl.isIndexing) {
            return false;
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        String validURL = rootURL + URL;
        String urlPath = URL;
        if (!rootURL.equals(site.getUrl()) && URL.equals("/")) {
            urlPath = rootURL;
        }
        if (!pageProcessor.existsByPathAndSite(urlPath)) {
            Document doc = pageProcessor.getDocFromUrl(validURL);
            if (doc != null) {
                pageProcessor.indexPage(doc, false);
                Elements elements = doc.select("a");
                forkLinks(elements);
            }
        }
        return true;
    }

    private void forkLinks(Elements elements) {
        for (Element element : elements) {
//            String href = element.attributes().get("href");
            String path = element.attr("abs:href");
            String purePath = pageProcessor.deletePrefix(path);
            String pureSiteUrl = pageProcessor.deletePrefix(site.getUrl());
            if (!purePath.startsWith(pureSiteUrl)) {
                continue;
            }
            String href = purePath.replaceAll(pureSiteUrl, "");
            if (!href.contains("?") && !href.contains("#") && !href.isEmpty()
                    && (!href.contains(":"))) {
                if (!pageProcessor.existsByPathAndSite(href)) {
                    LinksProcessor task;
                    task = new LinksProcessor(rootURL, href, site, pageProcessor);
                    task.fork();
                }
            }
        }
    }
}

