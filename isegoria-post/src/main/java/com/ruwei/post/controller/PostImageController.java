package com.ruwei.post.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.model.vo.ImageUploadVO;
import com.ruwei.post.service.PostImageService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/postImage")
@SaCheckLogin
public class PostImageController {

    @Resource
    private PostImageService postImageService;

    /**
     * 上传帖子图片。
     */
    @PostMapping("/upload")
    @RateLimit(limit = 10, window = 60, prefix = "upload")
    public BaseResponse<ImageUploadVO> uploadImage(@RequestParam("file") MultipartFile file) {
        return ResultUtils.success(postImageService.uploadImage(file));
    }


}
