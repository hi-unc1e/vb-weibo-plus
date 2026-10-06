package xyz.fz.weibo.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.fz.weibo.domain.DailyBriefView;
import xyz.fz.weibo.service.DailyBriefService;

import java.time.LocalDate;

@RestController
@RequestMapping("/post/daily-brief")
public class DailyBriefController {

    private final DailyBriefService service;

    public DailyBriefController(DailyBriefService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DailyBriefView> get(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DailyBriefView view = service.get(date);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    @PostMapping
    public DailyBriefView generate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.generate(date);
    }
}
