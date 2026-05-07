package org.example.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轻量就绪探针：HTTP 端口监听即可返回 200，供 Makefile / 编排脚本探测。
 * Milvus 连通性请使用 {@link MilvusCheckController} /milvus/health。
 */
@RestController
public class ReadyController {

    @GetMapping("/health/ready")
    public ResponseEntity<String> ready() {
        return ResponseEntity.ok("ok");
    }
}
