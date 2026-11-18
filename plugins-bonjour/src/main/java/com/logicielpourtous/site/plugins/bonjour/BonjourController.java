package com.logicielpourtous.site.plugins.bonjour;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/bonjour")
public class BonjourController {
    @GetMapping
    public Map<String, String> bonjour() {
        return Map.of("message", "Bonjour, version 1.0.1 mise a jour a chaud", "version", "1.0.1");
    }
}
