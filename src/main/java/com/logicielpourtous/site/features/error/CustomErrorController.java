package com.logicielpourtous.site.features.error;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        
        if (status != null) {
            Integer statusCode = Integer.valueOf(status.toString());
            model.addAttribute("statusCode", statusCode);
            
            if(statusCode == HttpStatus.NOT_FOUND.value()) { // 404
                return "error/404";
            }
            else if(statusCode == HttpStatus.FORBIDDEN.value()) { // 403
                return "error/403";
            }
            else if(statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) { // 500
                return "error/500";
            }
        }
        return "error/default"; // Page d'erreur générique
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "error/403";
    }

}
