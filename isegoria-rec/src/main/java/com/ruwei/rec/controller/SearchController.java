package com.ruwei.rec.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.model.vo.PostBrowseVO;
import com.ruwei.rec.service.EsSearchService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private EsSearchService esSearchService;


    @GetMapping("/post")
    public BaseResponse<Page<PostBrowseVO>> searchPost(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "score") String sort,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ResultUtils.success(esSearchService.searchPost(keyword, boardId, type, sort, current, pageSize));
    }

}