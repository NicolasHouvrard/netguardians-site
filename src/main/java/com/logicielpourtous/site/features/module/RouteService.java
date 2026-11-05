package com.logicielpourtous.site.features.module;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Service
public class RouteService {

    @Autowired
    private ApplicationContext ctx;

    /** Ancienne méthode conservée pour compatibilité */
    public List<String> getControllerBasePaths() {
        return allMappings()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream()
                        .map(hm -> hm.getBeanType().getAnnotation(RequestMapping.class))
                        .filter(Objects::nonNull)
                        .flatMap(rm -> Arrays.stream(rm.value())))
                .filter(path -> !path.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Routes utilisables comme URL de module.
     * Parcourt tous les RequestMappingHandlerMapping du contexte,
     * extrait les chemins réels, tronque avant le premier /{variable}.
     */
    public List<String> getModuleBasePaths() {
        return allMappings()
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .flatMap(info -> Stream.concat(
                        info.getDirectPaths().stream(),
                        info.getPatternValues().stream()
                ))
                .filter(path -> path.startsWith("/intra") || path.startsWith("/espace")
                             || path.startsWith("/admin") || path.startsWith("/api"))
                .map(path -> {
                    int varIdx = path.indexOf("/{");
                    String base = varIdx > 0 ? path.substring(0, varIdx) : path;
                    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                })
                .filter(path -> !path.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private Stream<RequestMappingHandlerMapping> allMappings() {
        return ctx.getBeansOfType(RequestMappingHandlerMapping.class)
                  .values()
                  .stream();
    }
}
