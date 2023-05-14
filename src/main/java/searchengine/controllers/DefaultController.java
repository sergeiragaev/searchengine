package searchengine.controllers;

import com.github.tsohr.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class DefaultController {

    /**
     * Метод формирует страницу из HTML-файла index.html,
     * который находится в папке resources/templates.
     * Это делает библиотека Thymeleaf.
     */
    @RequestMapping("/")
    public String index() {
        return "index";
    }
    @RequestMapping("/*")
    public ResponseEntity<?> pageNotFound() {
        JSONObject response = new JSONObject();
        response.put("result", false);
        response.put("error", "Указанная страница не найдена");
        return new ResponseEntity<>(response.toString(), HttpStatus.NOT_FOUND);
    }

}
