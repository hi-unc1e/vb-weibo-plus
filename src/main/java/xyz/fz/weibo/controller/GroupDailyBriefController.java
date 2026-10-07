package xyz.fz.weibo.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.fz.weibo.domain.GroupDailyBriefView;
import xyz.fz.weibo.service.GroupDailyBriefService;

import java.time.LocalDate;

@RestController
@RequestMapping("/chat/daily-brief")
public class GroupDailyBriefController {

    private final GroupDailyBriefService service;

    public GroupDailyBriefController(GroupDailyBriefService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<GroupDailyBriefView> get(@RequestParam long gid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        GroupDailyBriefView view = service.get(gid, date);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    @PostMapping
    public GroupDailyBriefView generate(@RequestParam long gid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.generate(gid, date);
    }
}
