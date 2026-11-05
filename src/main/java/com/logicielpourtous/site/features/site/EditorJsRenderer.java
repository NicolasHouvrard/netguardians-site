package com.logicielpourtous.site.features.site;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


@Service
public class EditorJsRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    public String render(String jsonContent) {
        if (jsonContent == null || jsonContent.isEmpty() || jsonContent.equals("{}")) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(jsonContent);
            JsonNode blocks = root.get("blocks");
            return renderBlocks(blocks);
        } catch (Exception e) {
            return "<p class='text-danger'>Erreur de rendu du contenu.</p>";
        }
    }

    private String renderBlocks(JsonNode blocks) {
        if (blocks == null || !blocks.isArray()) return "";
        
        StringBuilder html = new StringBuilder();
        for (JsonNode block : blocks) {
            String type = block.get("type").asText();
            JsonNode data = block.get("data");

            switch (type) {
                case "paragraph" -> html.append("<p>").append(data.get("text").asText()).append("</p>");
                
                case "header" -> {
                    int level = data.get("level").asInt();
                    html.append("<h").append(level).append(">")
                        .append(data.get("text").asText())
                        .append("</h").append(level).append(">");
                }
                
                case "list" -> {
                    String tag = data.get("style").asText().equals("ordered") ? "ol" : "ul";
                    html.append("<").append(tag).append(">");
                    for (JsonNode item : data.get("items")) {
                        html.append("<li>").append(item.asText()).append("</li>");
                    }
                    html.append("</").append(tag).append(">");
                }

                case "layout" -> {
                    html.append("<div class='row'>");
                    if (data.has("items")) {
                        for (JsonNode column : data.get("items")) {
                            html.append("<div class='col'>");
                            // Appel récursif pour rendre les blocs à l'intérieur de la colonne
                            html.append(renderBlocks(column.get("blocks")));
                            html.append("</div>");
                        }
                    }
                    html.append("</div>");
                }
                
                case "quote" -> html.append("<blockquote class='blockquote border-start ps-3'>")
                                    .append("<p>").append(data.get("text").asText()).append("</p>")
                                    .append("<footer class='blockquote-footer'>").append(data.get("caption").asText()).append("</footer>")
                                    .append("</blockquote>");
                case "columns" -> {
                    html.append("<div class=' row'>"); // Utilise Bootstrap 'row'
                    JsonNode cols = data.get("cols");
                    if (cols != null && cols.isArray()) {
                        for (JsonNode col : cols) {
                            // On calcule la largeur (ex: si 2 colonnes, on met col-md-6)
                            int bootstrapColSize = 12 / cols.size(); 
                            html.append("<div class='tableau col-md-").append(bootstrapColSize).append("'>");
                            
                            // On rend les blocs à l'intérieur de cette colonne (Récursion)
                            if (col.has("blocks")) {
                                html.append(renderBlocks(col.get("blocks")));
                            }
                            
                            html.append("</div>");
                        }
                    }
                    html.append("</div>");
                }
                case "image" -> {
                    String url = data.get("file").get("url").asText();
                    boolean stretched = data.has("stretched") && data.get("stretched").asBoolean();
                    boolean withBorder = data.has("withBorder") && data.get("withBorder").asBoolean();
                    boolean withBackground = data.has("withBackground") && data.get("withBackground").asBoolean();

                    html.append("<div class='text-center my-4'>"); // Conteneur pour centrer
                    
                    // LOGIQUE DE TAILLE :
                    // Si 'stretched' est actif -> w-100 (Pleine largeur)
                    // Sinon -> w-50 ou w-75 (Image réduite et centrée)
                    String sizeClass = stretched ? "w-100" : "w-50 mx-auto";

                    html.append("<img src='").append(url).append("' ");
                    html.append("class='img-fluid rounded ").append(sizeClass);
                    
                    if (withBorder) html.append(" border shadow-sm");
                    if (withBackground) html.append(" p-3 bg-light");
                    
                    html.append("' alt='image'>");
                    html.append("</div>");
                }
                        }
                    }
                    return html.toString();
                }
}