package com.mopl.global.exception;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExceptionHandlerTestController {

    @GetMapping("/test/exception-handler/params")
    public String checkParams(
        @RequestParam int limit,
        @RequestParam String sortBy,
        @RequestParam String sortDirection,
        @RequestParam UUID idAfter
    ) {
        return "ok";
    }

}
