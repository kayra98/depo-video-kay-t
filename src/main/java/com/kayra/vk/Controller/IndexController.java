package com.kayra.vk.Controller;

import com.kayra.vk.Model.User;
import com.kayra.vk.Repository.RecordListRepository;
import com.kayra.vk.Repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {

    private final UserRepository userRepository;
    private final RecordListRepository recordListRepository;

    public IndexController(UserRepository userRepository,
                           RecordListRepository recordListRepository) {
        this.userRepository = userRepository;
        this.recordListRepository = recordListRepository;
    }

    @GetMapping({"/", "/index"})
    public String index(Model model, Authentication auth) {
        if (auth != null && auth.isAuthenticated()) {
            String email = auth.getName();
            userRepository.findByEmail(email).ifPresent(user -> {
                model.addAttribute("user", user);
            });
        }
        long totalRecordings = recordListRepository.count();
        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("totalRecordings", totalRecordings);
        return "index";
    }
}
