package com.ruwei.post.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.io.FileTypeUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.model.entity.PostImage;
import com.ruwei.model.vo.ImageUploadVO;
import com.ruwei.common.core.BusinessException;
import com.ruwei.post.manager.ObjectStorageManager;
import com.ruwei.post.manager.UploadResult;
import com.ruwei.post.mapper.PostImageMapper;
import com.ruwei.post.service.PostImageService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 帖子图片业务：校验、COS key 生成、上传结果组装。
 */
@Service
public class PostImageServiceImpl extends ServiceImpl<PostImageMapper, PostImage>
        implements PostImageService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    private static final Set<String> ALLOWED_TYPES = Set.of("jpg", "jpeg", "png", "webp", "gif");

    private static final Map<String, String> TYPE_MIME = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource
    private ObjectStorageManager objectStorageManager;


    @Override
    public ImageUploadVO uploadImage(MultipartFile file) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "图片不能为空");
        ThrowUtils.throwIf(file.getSize() > MAX_FILE_SIZE, ErrorCode.PARAMS_ERROR, "图片大小不能超过5MB");

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取上传图片失败");
        }

        // 校验真实文件头，不信任前端文件名和 Content-Type
        String fileType = FileTypeUtil.getType(new ByteArrayInputStream(bytes));
        ThrowUtils.throwIf(!ALLOWED_TYPES.contains(fileType), ErrorCode.PARAMS_ERROR,
                "仅支持jpg/png/webp/gif格式的图片");

        String ext = "jpeg".equals(fileType) ? "jpg" : fileType;
        long loginId = StpUtil.getLoginIdAsLong();
        String key = loginId + "/" + LocalDate.now().format(DATE_FORMAT)
                + "/" + IdUtil.simpleUUID() + "." + ext;

        UploadResult result;
        try {
            result = objectStorageManager.uploadImage(key, bytes, TYPE_MIME.get(fileType));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        }

        return buildUploadVO(result);
    }

    private ImageUploadVO buildUploadVO(UploadResult result) {
        ImageUploadVO vo = new ImageUploadVO();
        vo.setUrl(result.displayUrl());
        vo.setWidth(result.width());
        vo.setHeight(result.height());
        vo.setSize(result.size());
        vo.setMime(result.contentType());
        return vo;
    }
}
