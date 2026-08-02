package com.kayra.vk.Controller;

import com.kayra.vk.Model.RecordList;
import com.kayra.vk.Repository.RecordListRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class FileManagerController {

    private final RecordListRepository recordRepo;
    private static final int PAGE_SIZE = 20;

    public FileManagerController(RecordListRepository recordRepo) {
        this.recordRepo = recordRepo;
    }

    @GetMapping("/files")
    public String filesPage(Model model) {
        model.addAttribute("pageTitle", "Files");
        return "files";
    }

    @GetMapping("/api/files")
    @ResponseBody
    public Map<String, Object> listFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String search) {
        PageRequest pageRequest = PageRequest.of(page, PAGE_SIZE, Sort.by("createdAt").descending());
        Page<RecordList> result;
        if (search.isBlank()) {
            result = recordRepo.findAllByOrderByCreatedAtDesc(pageRequest);
        } else {
            result = recordRepo.findByOrderNoContainingIgnoreCaseOrderByCreatedAtDesc(
                    search.trim(), pageRequest);
        }
        return Map.of(
                "content", result.getContent(),
                "totalPages", result.getTotalPages(),
                "totalElements", result.getTotalElements(),
                "currentPage", result.getNumber()
        );
    }
}
