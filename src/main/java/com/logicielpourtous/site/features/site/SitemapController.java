package com.logicielpourtous.site.features.site;

import com.logicielpourtous.site.features.pages.PageType;
import com.logicielpourtous.site.features.pages.PublicPage;
import com.logicielpourtous.site.features.pages.PublicPageRepository;
import com.logicielpourtous.site.features.shop.ShopProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
public class SitemapController {

    private final PublicPageRepository  publicPageRepository;
    private final ShopProductRepository shopProductRepository;

    @Value("${shop.base-url:http://localhost:8080}")
    private String baseUrl;

    public SitemapController(PublicPageRepository publicPageRepository,
                             ShopProductRepository shopProductRepository) {
        this.publicPageRepository  = publicPageRepository;
        this.shopProductRepository = shopProductRepository;
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String getSitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");

        // Accueil
        xml.append("<url><loc>").append(baseUrl).append("/</loc><priority>1.0</priority></url>");

        // Nouvelles pages publiques (/{slug}) — exclut la homepage et les non-publiées
        List<PublicPage> pages = publicPageRepository
                .findByPageTypeAndPublishedTrueOrderByPublishedAtDesc(PageType.PAGE);
        for (PublicPage page : pages) {
            if (!page.isHomePage()) {
                xml.append("<url><loc>").append(baseUrl).append("/").append(page.getSlug())
                   .append("</loc><changefreq>monthly</changefreq><priority>0.8</priority></url>");
            }
        }

        // Articles de blog (/blog/{slug})
        List<PublicPage> blogPosts = publicPageRepository
                .findByPageTypeAndPublishedTrueOrderByPublishedAtDesc(PageType.BLOG_POST);
        for (PublicPage post : blogPosts) {
            xml.append("<url><loc>").append(baseUrl).append("/blog/").append(post.getSlug())
               .append("</loc><changefreq>monthly</changefreq><priority>0.7</priority></url>");
        }
        if (!blogPosts.isEmpty()) {
            xml.append("<url><loc>").append(baseUrl).append("/blog</loc><changefreq>weekly</changefreq><priority>0.6</priority></url>");
        }

        // Boutique
        xml.append("<url><loc>").append(baseUrl).append("/boutique</loc><changefreq>daily</changefreq><priority>0.8</priority></url>");
        for (var sp : shopProductRepository.findPublishedOrderByDisplayOrder()) {
            xml.append("<url><loc>").append(baseUrl).append("/boutique/").append(sp.getSlug())
               .append("</loc><changefreq>weekly</changefreq><priority>0.6</priority></url>");
        }

        xml.append("</urlset>");
        return xml.toString();
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String getRobotsTxt() {
        return "User-agent: *\n" +
               "Disallow: /admin/\n" +
               "Disallow: /intra/\n" +
               "Disallow: /espace/\n" +
               "Disallow: /api/\n" +
               "Allow: /\n" +
               "\n" +
               "Sitemap: " + baseUrl + "/sitemap.xml\n";
    }
}