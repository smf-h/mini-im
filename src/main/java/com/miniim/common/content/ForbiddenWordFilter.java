package com.miniim.common.content;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 违禁词过滤器（服务端替换）。
 *
 * <p>当前策略：按“包含子串”匹配，命中则替换为 {@code ***}。</p>
 */
@Slf4j
@Component
public class ForbiddenWordFilter {

    private static final String MASK = "***";

    private final List<String> words;

    public ForbiddenWordFilter(@Value("classpath:forbidden-words.txt") Resource resource) {
        this.words = Collections.unmodifiableList(load(resource));
        if (!this.words.isEmpty()) {
            log.info("forbidden words loaded: {} items", this.words.size());
        } else {
            log.info("forbidden words empty");
        }
    }

    public String sanitize(String text) {
        if (text == null || text.isEmpty() || words.isEmpty()) {
            return text;
        }
        String out = text;
        for (String w : words) {
            if (w == null || w.isEmpty()) {
                continue;
            }
            if (out.contains(w)) {
                out = out.replace(w, MASK);
            }
        }
        return out;
    }

    private static List<String> load(Resource resource) {
        if (resource == null) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                set.add(s);
            }
        } catch (Exception e) {
            log.warn("load forbidden words failed: {}", e.toString());
            return List.of();
        }

        List<String> list = new ArrayList<>(set);
        list.sort((a, b) -> {
            int la = a == null ? 0 : a.length();
            int lb = b == null ? 0 : b.length();
            int cmp = Integer.compare(lb, la);
            if (cmp != 0) return cmp;
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return a.compareTo(b);
        });
        return list;
    }
}
